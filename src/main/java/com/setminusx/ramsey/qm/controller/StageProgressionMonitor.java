package com.setminusx.ramsey.qm.controller;

import com.setminusx.ramsey.qm.client.MiddlewareClient;
import com.setminusx.ramsey.qm.config.RamseyConfig;
import com.setminusx.ramsey.qm.model.BestResult;
import com.setminusx.ramsey.qm.model.Edge;
import com.setminusx.ramsey.qm.model.Graph;
import com.setminusx.ramsey.qm.model.ProgressionPoint;
import com.setminusx.ramsey.qm.model.Stage;
import com.setminusx.ramsey.qm.service.RedisQueueService;
import com.setminusx.ramsey.qm.utility.CliqueCounter;
import com.setminusx.ramsey.qm.utility.GraphHashUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Monitors for improved results and triggers stage progression.
 * Handles two scenarios:
 * 1. Improvement found: best result has fewer cliques than base graph
 * 2. Stage exhausted: all work claimed, picks best unprocessed result
 */
@Slf4j
@Component
public class StageProgressionMonitor {

    private final MiddlewareClient middlewareClient;
    private final RedisQueueService redisQueueService;
    private final RamseyConfig ramseyConfig;

    // Track when each stage was first detected as exhausted
    private final Map<Integer, Instant> exhaustionDetectedAt = new ConcurrentHashMap<>();

    public StageProgressionMonitor(
            MiddlewareClient middlewareClient,
            RedisQueueService redisQueueService,
            RamseyConfig ramseyConfig) {
        this.middlewareClient = middlewareClient;
        this.redisQueueService = redisQueueService;
        this.ramseyConfig = ramseyConfig;
    }

    @Scheduled(fixedRateString = "${ramsey.stage-progression.frequency-in-millis:30000}")
    public void checkForProgression() {
        log.debug("Checking for stage progression");

        // Single campaign-agnostic QM: progress EVERY campaign's active stage.
        // Per-stage state (Redis keys, exhaustion tracking) is stage-scoped, so
        // this is just a loop over the existing per-stage logic.
        for (Stage currentStage : middlewareClient.getActiveStages()) {
            synchronized (lockFor(currentStage.getCampaignId())) {
                checkStageForProgression(currentStage);
            }
        }
    }

    /**
     * Settle timer fired for a stage: adopt the best result available now. Triggered by
     * {@link StageAdoptScheduler} a deliberate settle window after a worker announced the stage's
     * first new best, which is both earlier and more precisely timed than the polling loop (whose
     * settle time is random 0..pollInterval and whose interval also floors the stage duration).
     *
     * <p>Runs the SAME per-stage logic as the loop, under the same per-campaign lock, so this is
     * purely a better-timed trigger rather than a second code path. No-ops if the stage is no
     * longer active (the loop or another timer already advanced it).
     */
    public void adoptAfterSettle(int stageId) {
        for (Stage stage : middlewareClient.getActiveStages()) {
            if (stage.getStageId() != null && stage.getStageId() == stageId) {
                synchronized (lockFor(stage.getCampaignId())) {
                    checkStageForProgression(stage);
                }
                return;
            }
        }
    }

    /**
     * Per-campaign lock serializing everything that ADVANCES a stage. The progression
     * (30s) and perturbation (60s) loops run on separate scheduler threads (pool size 100),
     * so without this they can both advance the same stage in the same instant and leave the
     * campaign with TWO ACTIVE stages — two competing lineages that then progress forever in
     * lockstep (observed on campaign 10: a kick and a normal advance 10ms apart). Locking per
     * campaign (not globally) keeps unrelated campaigns progressing in parallel.
     */
    private Object lockFor(Integer campaignId) {
        return campaignLocks.computeIfAbsent(campaignId, k -> new Object());
    }

    private final Map<Integer, Object> campaignLocks = new ConcurrentHashMap<>();

    private void checkStageForProgression(Stage currentStage) {
        Integer stageId = currentStage.getStageId();

        // Get current base graph
        Graph baseGraph = middlewareClient.getGraphById(currentStage.getBaseGraphId());
        if (baseGraph == null || baseGraph.getCliqueCount() == null) {
            log.warn("Could not fetch base graph or clique count for stage {}", stageId);
            return;
        }

        // SCENARIO 1: improvement found (best result beats base). Only acted on when immediate
        // progression is enabled; when disabled the stage runs to exhaustion (SCENARIO 2) and the
        // best result across the whole work space is taken at the end.
        if (ramseyConfig.getStage().isImmediatelyProgressOnImprovement()
                && tryProgressOnImprovement(currentStage, baseGraph, stageId)) {
            return;
        }

        // SCENARIO 2: Check for exhaustion
        if (redisQueueService.isStageExhausted(stageId)) {
            handleExhaustedStage(currentStage, baseGraph, stageId);
        } else {
            // Not exhausted yet, clear any stale exhaustion tracking
            exhaustionDetectedAt.remove(stageId);
        }
    }

    /**
     * SCENARIO 1: if the best result beats the base graph and hasn't already been processed,
     * advance the stage to it. Returns true if the stage was progressed.
     */
    private boolean tryProgressOnImprovement(Stage currentStage, Graph baseGraph, Integer stageId) {
        List<BestResult> topResults = redisQueueService.getTopResults(stageId, 1);
        if (topResults.isEmpty()) {
            return false;
        }
        BestResult best = topResults.getFirst();
        if (best.getCliqueCount() >= baseGraph.getCliqueCount()) {
            return false;
        }

        // Guard against oscillation: don't progress to a graph we've already processed
        // (this prevents reverting when exhaustion handling picks a worse graph).
        String graphHash = best.isSimulatedAnnealingResult()
                ? GraphHashUtil.computeHash(best.getGraphBitstring())
                : GraphHashUtil.computeDerivedGraphHash(baseGraph, best.getEdgesToFlip());

        if (redisQueueService.isGraphAlreadyProcessed(graphHash)) {
            log.info("Improvement found (cliques={}) but graph already processed, treating as exhaustion case",
                    best.getCliqueCount());
            return false; // fall through to exhaustion handling
        }

        log.info("IMPROVEMENT FOUND! Stage {} base graph has {} cliques, best result has {} (source: {})",
                stageId, baseGraph.getCliqueCount(), best.getCliqueCount(),
                best.isSimulatedAnnealingResult() ? "SA" : "EXHAUSTIVE");
        exhaustionDetectedAt.remove(stageId); // Clear any exhaustion tracking
        progressStageWithHash(currentStage, baseGraph, best, graphHash);
        return true;
    }

    /**
     * Handle an exhausted stage - wait for delay, then pick best unprocessed
     * result.
     */
    private void handleExhaustedStage(Stage currentStage, Graph baseGraph, Integer stageId) {
        long totalPairs = redisQueueService.getStageTotalPairs(stageId);
        long processed = redisQueueService.getProcessedCount(stageId);

        if (isFullyProcessed(processed, totalPairs)) {
            // All claimed work units have been processed AND their results published (workers
            // increment processed_count only after submitting), so there are no in-flight
            // stragglers to wait for. Skip the exhaustion delay and advance immediately.
            exhaustionDetectedAt.remove(stageId);
            log.info("Stage {} fully processed ({}/{}) — advancing without exhaustion delay", stageId, processed, totalPairs);
        } else {
            // Work is fully claimed but not fully processed — likely a restarted/in-flight worker
            // whose claimed units may never report. We cannot wait on processed_count forever, so
            // wait out the exhaustion delay, then fall back to the best published result.
            Long delayMs = ramseyConfig.getStage().getExhaustionDelayMs();
            Instant detectedAt = exhaustionDetectedAt.computeIfAbsent(stageId, k -> {
                log.info("Stage {} claimed-exhausted but only {}/{} processed; starting {}ms straggler delay...",
                        stageId, processed, totalPairs, delayMs);
                return Instant.now();
            });
            long elapsedMs = Instant.now().toEpochMilli() - detectedAt.toEpochMilli();
            if (elapsedMs < delayMs) {
                log.debug("Stage {} straggler delay: {}ms / {}ms elapsed ({}/{} processed)",
                        stageId, elapsedMs, delayMs, processed, totalPairs);
                return;
            }
            log.info("Stage {} straggler delay complete ({}/{} processed), selecting best unprocessed result...",
                    stageId, processed, totalPairs);
        }

        // Get top N results
        int topCount = ramseyConfig.getStage().getTopResultsCount();
        List<BestResult> topResults = redisQueueService.getTopResults(stageId, topCount);

        if (topResults.isEmpty()) {
            log.warn("Stage {} exhausted but no results in sorted set! Cannot progress.", stageId);
            exhaustionDetectedAt.remove(stageId);
            return;
        }

        // Find the first unprocessed result
        for (BestResult result : topResults) {
            String graphHash = result.isSimulatedAnnealingResult()
                    ? GraphHashUtil.computeHash(result.getGraphBitstring())
                    : GraphHashUtil.computeDerivedGraphHash(baseGraph, result.getEdgesToFlip());

            if (!redisQueueService.isGraphAlreadyProcessed(graphHash)) {
                log.info("Progressing exhausted stage {} to best unprocessed result: cliques={}",
                        stageId, result.getCliqueCount());
                exhaustionDetectedAt.remove(stageId);
                progressStageWithHash(currentStage, baseGraph, result, graphHash);
                return;
            } else {
                log.debug("Result with cliques={} already processed, skipping", result.getCliqueCount());
            }
        }

        // All top results have been processed
        log.warn("Stage {} exhausted and ALL {} top results already processed! System is stuck.",
                stageId, topResults.size());
        exhaustionDetectedAt.remove(stageId);
        // TODO: Could mark campaign as STUCK or send alert
    }

    private void progressStageWithHash(Stage currentStage, Graph baseGraph, BestResult bestResult, String graphHash) {
        // 1. Get or construct the derived graph
        Graph derivedGraph;
        String source;

        if (bestResult.isSimulatedAnnealingResult()) {
            log.info("Progressing via SIMULATED_ANNEALING result from base graph {}", bestResult.getBaseGraphId());
            source = "SIMULATED_ANNEALING";
            derivedGraph = new Graph();
            derivedGraph.setEdgeData(bestResult.getGraphBitstring());
            derivedGraph.setVertexCount(baseGraph.getVertexCount());
            derivedGraph.setSubgraphSize(baseGraph.getSubgraphSize());
        } else {
            String edgesToFlipStr = formatEdgesForUrl(bestResult.getEdgesToFlip());
            log.info("Progressing via EXHAUSTIVE result from base {} with edges {}",
                    bestResult.getBaseGraphId(), edgesToFlipStr);
            source = "EXHAUSTIVE";
            derivedGraph = middlewareClient.getDerivedGraph(
                    bestResult.getBaseGraphId(), edgesToFlipStr);

            if (derivedGraph == null) {
                log.error("Failed to get derived graph");
                return;
            }
        }

        // 2. Set the clique count on the derived graph
        derivedGraph.setCliqueCount(bestResult.getCliqueCount());

        // 3. Record the graph hash as processed (before creating to avoid race)
        redisQueueService.addProcessedGraphHash(graphHash);

        // 4. Save the derived graph to DB
        log.info("Saving derived graph with clique count {}", bestResult.getCliqueCount());
        Graph savedGraph = middlewareClient.createGraph(derivedGraph);
        log.info("Created new graph with ID: {}", savedGraph.getGraphId());

        switchToNewStage(currentStage, savedGraph, "source: " + source);
    }

    /**
     * Deactivate the current stage and activate a new one based on savedGraph
     * (shared by normal progression and perturbation kicks): create stage, init
     * Redis counters, clear the old stage's keys.
     */
    /**
     * Advance a campaign: deactivate {@code currentStage} and create a new ACTIVE stage on
     * {@code savedGraph}. Returns null (advancing nothing) if the stage is no longer ACTIVE —
     * both scheduled loops iterate a snapshot of getActiveStages() taken before they acquire
     * the campaign lock, so a stage can already have been advanced by the other loop by the
     * time we get here. Advancing it again would create a SECOND active stage.
     */
    private Stage switchToNewStage(Stage currentStage, Graph savedGraph, String details) {
        if (!isStillActive(currentStage)) {
            log.info("Stage {} is no longer ACTIVE (already advanced); skipping advance ({})",
                    currentStage.getStageId(), details);
            return null;
        }

        // Mark current stage as INACTIVE
        log.info("Marking stage {} as INACTIVE", currentStage.getStageId());
        currentStage.setStatus(Stage.Status.INACTIVE);
        middlewareClient.updateStage(currentStage);

        // Create new active stage with default enumeration strategy
        Stage newStage = new Stage();
        newStage.setStatus(Stage.Status.ACTIVE);
        newStage.setBaseGraphId(savedGraph.getGraphId());
        newStage.setCampaignId(currentStage.getCampaignId());
        newStage.setDetails(details);

        // Apply default work enumeration strategy if configured
        String defaultStrategy = ramseyConfig.getStage().getDefaultWorkEnumerationStrategy();
        if (defaultStrategy != null && !defaultStrategy.isBlank()) {
            newStage.setWorkEnumerationStrategy(defaultStrategy);
            log.info("Setting work enumeration strategy to: {}", defaultStrategy);
        }

        Stage createdStage = middlewareClient.createStage(newStage);
        log.info("Created new stage {} with base graph {} ({})",
                createdStage.getStageId(), savedGraph.getGraphId(), details);

        // Initialize counter-based mode for new stage (before clearing old stage)
        if (createdStage.getWorkEnumerationStrategy() != null) {
            initializeRedisForStage(createdStage, savedGraph);
        }

        // Clear Redis queue and counter keys for old stage
        log.info("Clearing Redis keys for old stage {}", currentStage.getStageId());
        redisQueueService.clearQueue(currentStage.getStageId());
        redisQueueService.clearStageCounter(currentStage.getStageId());
        redisQueueService.deleteBestResult(currentStage.getStageId());
        redisQueueService.deleteTopResults(currentStage.getStageId());

        // Tell the fleet immediately rather than letting each worker discover it on its next
        // poll — at current stage rates that lag is a large slice of a stage's lifetime.
        redisQueueService.publishStageAdvanced(createdStage.getCampaignId(), createdStage.getStageId());

        log.info("Stage progression complete! New stage {} is now active", createdStage.getStageId());
        return createdStage;
    }

    ////////////////////////////////////////////////////////////////////////////////
    // Perturbation (Iterated Local Search "kick") //
    ////////////////////////////////////////////////////////////////////////////////

    // Per-campaign: the stage id of the last kick, and how many consecutive kicks
    // have happened without a new campaign minimum (drives escalation). These live in
    // memory but are REHYDRATED from persisted history on first use after a restart
    // (hydrateKickStateFromHistory), so a QM restart resumes the wall/escalation instead
    // of firing an early kick and resetting the escalation ladder.
    private final Map<Integer, Integer> lastKickStageId = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> fruitlessKicks = new ConcurrentHashMap<>();
    private final java.util.Random perturbationRandom = new java.util.Random();
    // Kick stages are recognized by this details prefix (set when a kick creates its stage).
    static final String KICK_DETAILS_PREFIX = "PERTURBATION";

    /**
     * ILS kick: when a campaign has gone {@code wallStages} stages with no new
     * minimum, restart its descent from a randomly perturbed (balance-preserving)
     * copy of the campaign's BEST graph — the incumbent, never the drifted current
     * base. Consecutive fruitless kicks escalate the kick strength.
     */
    @Scheduled(fixedRateString = "${ramsey.perturbation.frequency-in-millis:300000}")
    public void checkForPerturbation() {
        if (!ramseyConfig.getPerturbation().isEnabled()) {
            return;
        }
        for (Stage stage : middlewareClient.getActiveStages()) {
            try {
                synchronized (lockFor(stage.getCampaignId())) {
                    maybePerturbCampaign(stage);
                }
            } catch (Exception e) {
                log.warn("Perturbation check failed for campaign {}: {}", stage.getCampaignId(), e.toString());
            }
        }
    }

    private void maybePerturbCampaign(Stage currentStage) {
        RamseyConfig.Perturbation cfg = ramseyConfig.getPerturbation();
        Integer campaignId = currentStage.getCampaignId();

        List<ProgressionPoint> history = middlewareClient.getProgression(campaignId);
        if (history.size() < cfg.getBasinStaleStages()) {
            return; // too young to have stalled
        }

        // Campaign minimum (the incumbent) and the first stage that achieved it. This is what a
        // kick restarts FROM, and what decides escalation — it is NOT the staleness clock.
        ProgressionPoint minPoint = minPointFrom(history, Integer.MIN_VALUE);
        if (minPoint == null) {
            return;
        }

        // Recover kick state after a restart before the gate, so the basin window below starts at
        // the right stage and we don't re-kick early.
        hydrateKickStateFromHistory(campaignId, history, minPoint);
        Integer lastKick = lastKickStageId.get(campaignId);

        // Staleness is measured inside the CURRENT BASIN — stages since this basin's own best
        // stage — not as a fixed count since the kick. A descent that is still finding new minima
        // therefore runs as long as it needs, and a basin that has flattened is kicked promptly.
        // The fixed stages-since-kick wall did both badly: it cut three campaign-10 descents off
        // while they were still in free fall (kicks 18/19/20 each bottomed out on their FINAL
        // stage, floors 58k/158k/81k versus a 25,758 incumbent), while flat basins idled for
        // thousands of stages after their floor had stopped moving.
        //
        // Before the first kick the basin IS the whole campaign, so this also subsumes the old
        // "stages since the campaign min" wall.
        int basinStart = lastKick == null ? Integer.MIN_VALUE : lastKick;
        ProgressionPoint basinMin = minPointFrom(history, basinStart);
        if (basinMin == null) {
            return;
        }
        long stagesSinceBasinMin = history.stream()
                .filter(p -> p.getStageId() > basinMin.getStageId())
                .count();
        if (stagesSinceBasinMin < cfg.getBasinStaleStages()) {
            resetKickTrackingIfImproved(campaignId, minPoint.getStageId());
            return; // basin is still improving
        }

        if (lastKick != null) {
            // The previous kick produced no new min (min stage predates the kick) -> escalate.
            if (minPoint.getStageId() < lastKick) {
                fruitlessKicks.merge(campaignId, 1, Integer::sum);
            } else {
                fruitlessKicks.remove(campaignId);
            }
        }

        // Geometric escalation: each consecutive fruitless kick doubles the strength
        // (x1, x2, x4, x8, ...) up to the cap. streak is capped before the shift to avoid
        // int overflow; the min with the cap bounds it regardless.
        int streak = fruitlessKicks.getOrDefault(campaignId, 0);
        int multiplier = Math.min(1 << Math.min(streak, 30), cfg.getEscalationCap());
        int pairs = cfg.getEdgePairs() * multiplier;

        Graph incumbent = middlewareClient.getGraphById(minPoint.getGraphId());
        if (incumbent == null || incumbent.getEdgeData() == null) {
            log.warn("Perturbation: could not fetch incumbent graph {} for campaign {}",
                    minPoint.getGraphId(), campaignId);
            return;
        }

        log.info("PERTURBATION: campaign {} basin stale ({} stages past basin floor {} @ stage {}; "
                        + "incumbent {} @ stage {}); kicking incumbent graph {} with {} edge pairs "
                        + "(escalation x{})",
                campaignId, stagesSinceBasinMin, basinMin.getCliqueCount(), basinMin.getStageId(),
                minPoint.getCliqueCount(), minPoint.getStageId(),
                incumbent.getGraphId(), pairs, multiplier);

        perturbAndAdvance(currentStage, incumbent, pairs, multiplier);
    }

    /**
     * Lowest-count point at or after {@code fromStageId}, earliest stage breaking ties.
     * With {@link Integer#MIN_VALUE} this is the campaign incumbent; with the last kick's stage
     * it is the current basin's floor. The kick stage itself is always a spike (a perturbed graph
     * counts far worse than the incumbent it came from), so including it never skews the min.
     */
    private static ProgressionPoint minPointFrom(List<ProgressionPoint> history, int fromStageId) {
        return history.stream()
                .filter(p -> p.getCliqueCount() != null && p.getStageId() >= fromStageId)
                .min(java.util.Comparator.comparingLong(ProgressionPoint::getCliqueCount)
                        .thenComparing(ProgressionPoint::getStageId))
                .orElse(null);
    }

    /**
     * True if the stage is still ACTIVE upstream. Fails OPEN on a middleware error: a transient
     * fetch failure shouldn't stall legitimate progression, and the campaign lock already covers
     * the common race.
     */
    private boolean isStillActive(Stage stage) {
        try {
            return middlewareClient.getActiveStages().stream()
                    .anyMatch(s -> stage.getStageId().equals(s.getStageId()));
        } catch (Exception e) {
            log.warn("Could not verify stage {} still ACTIVE ({}); proceeding", stage.getStageId(), e.toString());
            return true;
        }
    }

    private void resetKickTrackingIfImproved(Integer campaignId, Integer minStageId) {
        Integer lastKick = lastKickStageId.get(campaignId);
        if (lastKick != null && minStageId > lastKick) {
            fruitlessKicks.remove(campaignId); // the kick paid off
        }
    }

    /**
     * Restore in-memory kick state from the persisted progression after a QM restart, so a
     * restart doesn't skip the re-kick gate (firing an early kick) or reset the escalation
     * ladder. No-op once the campaign has in-memory state, or if it has never been kicked.
     * Kick stages are identified authoritatively by their {@code PERTURBATION} details
     * marker (a clique-count heuristic is unreliable: big kicks' descents drift wildly and
     * cross any threshold many times). Escalation streak = kicks after the current min,
     * minus the last one (whose own fruitfulness is only decided at the next kick), matching
     * the live counter.
     */
    void hydrateKickStateFromHistory(Integer campaignId, List<ProgressionPoint> history, ProgressionPoint minPoint) {
        if (lastKickStageId.containsKey(campaignId)) {
            return; // in-memory state is authoritative once present
        }
        List<Integer> kickStages = history.stream()
                .filter(p -> p.getDetails() != null && p.getDetails().startsWith(KICK_DETAILS_PREFIX))
                .map(ProgressionPoint::getStageId)
                .sorted()
                .toList();
        if (kickStages.isEmpty()) {
            return; // never kicked -> leave state empty (first-kick behavior)
        }
        int lastKick = kickStages.get(kickStages.size() - 1);
        lastKickStageId.put(campaignId, lastKick);
        long kicksAfterMin = kickStages.stream().filter(s -> s > minPoint.getStageId()).count();
        int streak = (int) Math.max(0, kicksAfterMin - 1);
        if (streak > 0) {
            fruitlessKicks.put(campaignId, streak);
        }
        log.info("PERTURBATION: rehydrated kick state for campaign {} from history "
                + "({} kicks, lastKick stage {}, fruitless streak {})",
                campaignId, kickStages.size(), lastKick, streak);
    }

    private void perturbAndAdvance(Stage currentStage, Graph incumbent, int pairs, int multiplier) {
        RamseyConfig.Perturbation cfg = ramseyConfig.getPerturbation();

        for (int attempt = 1; attempt <= cfg.getMaxNoveltyRetries(); attempt++) {
            String kicked = perturbBalanced(incumbent.getEdgeData(), pairs, perturbationRandom);
            String hash = GraphHashUtil.computeHash(kicked);
            if (redisQueueService.isGraphAlreadyProcessed(hash)) {
                log.info("Perturbation attempt {}: kicked graph already visited, retrying", attempt);
                continue;
            }

            long cliqueCount = CliqueCounter.countMonoCliques(
                    kicked, incumbent.getVertexCount(), incumbent.getSubgraphSize());

            Graph kickedGraph = new Graph();
            kickedGraph.setEdgeData(kicked);
            kickedGraph.setVertexCount(incumbent.getVertexCount());
            kickedGraph.setSubgraphSize(incumbent.getSubgraphSize());
            kickedGraph.setCliqueCount((int) cliqueCount);

            redisQueueService.addProcessedGraphHash(hash);
            Graph savedGraph = middlewareClient.createGraph(kickedGraph);
            log.info("PERTURBATION: kicked graph saved as {} (cliques {} vs incumbent {})",
                    savedGraph.getGraphId(), cliqueCount, incumbent.getCliqueCount());

            Stage created = switchToNewStage(currentStage, savedGraph,
                    "PERTURBATION kick from graph " + incumbent.getGraphId()
                            + " (" + incumbent.getCliqueCount() + "), pairs=" + pairs
                            + ", escalation=x" + multiplier);
            if (created == null) {
                // The stage was advanced by the progression loop first; skip this kick and
                // retry on a later tick (the campaign is still walled, so nothing is lost).
                log.info("Perturbation: campaign {} stage advanced concurrently; skipping kick",
                        currentStage.getCampaignId());
                return;
            }
            lastKickStageId.put(currentStage.getCampaignId(), created.getStageId());
            return;
        }
        log.warn("Perturbation: no novel kicked graph found for campaign {} after {} attempts",
                currentStage.getCampaignId(), cfg.getMaxNoveltyRetries());
    }

    /**
     * Balance-preserving random kick: flip {@code pairs} red edges to blue and the
     * same number of blue edges to red (the production engine's move-class invariant).
     */
    static String perturbBalanced(String bits, int pairs, java.util.Random random) {
        char[] chars = bits.toCharArray();
        List<Integer> redIdx = new java.util.ArrayList<>();
        List<Integer> blueIdx = new java.util.ArrayList<>();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == '1') {
                redIdx.add(i);
            } else {
                blueIdx.add(i);
            }
        }
        int p = Math.min(pairs, Math.min(redIdx.size(), blueIdx.size()));
        java.util.Collections.shuffle(redIdx, random);
        java.util.Collections.shuffle(blueIdx, random);
        for (int i = 0; i < p; i++) {
            chars[redIdx.get(i)] = '0';
            chars[blueIdx.get(i)] = '1';
        }
        return new String(chars);
    }

    /**
     * Total work units for a stage. Pair strategies use redCount * blueCount.
     * DUAL_EDGE_CARDINALITY_WITH_SINGLES prepends one work unit per edge of
     * EITHER color (singles = redCount + blueCount) — this MUST match the
     * Rust worker's DualCardinalityWithSinglesEnumerator; the worker refuses
     * the stage if the totals disagree.
     */
    static long computeTotalWorkUnits(long redCount, long blueCount, String strategy) {
        long pairs = redCount * blueCount;
        // Both ..._WITH_SINGLES strategies enumerate the same space — every edge as a single flip,
        // then every (red, blue) pair. They differ only in the ORDER within each block, so the
        // total is identical and the worker's cross-check passes either way.
        if ("DUAL_EDGE_CARDINALITY_WITH_SINGLES".equals(strategy)
                || "SEQUENTIAL_WITH_SINGLES".equals(strategy)) {
            return redCount + blueCount + pairs;
        }
        return pairs;
    }

    /**
     * True when every work unit of an exhausted (fully-claimed) stage has also been processed
     * and published, so the stage can advance immediately without waiting out the exhaustion
     * delay. Requires a known positive total; processedCount may slightly exceed totalPairs on
     * the final partial batch, hence the >= comparison.
     */
    static boolean isFullyProcessed(long processedCount, long totalPairs) {
        return totalPairs > 0 && processedCount >= totalPairs;
    }

    /**
     * Format edges for URL parameter: {{v1:v2},{v3:v4}}
     */
    private String formatEdgesForUrl(List<Edge> edges) {
        return "{" + edges.stream()
                .map(e -> "{" + e.getVertexOne() + ":" + e.getVertexTwo() + "}")
                .collect(Collectors.joining(",")) + "}";
    }

    /**
     * Periodically check that the active stage is properly initialized in Redis.
     * This safeguards against Redis data loss (restarts/flushes).
     */
    @Scheduled(fixedRateString = "${ramsey.work-unit.queue.frequency-in-millis:5000}")
    public void ensureActiveStageInitialized() {
        // Single QM: ensure the Redis stage_config exists for EVERY campaign's
        // active stage (safeguards against Redis data loss for any of them).
        List<Stage> stages = middlewareClient.getActiveStages();

        if (stages.isEmpty()) {
            return;
        }

        for (Stage stage : stages) {
            if (stage.getWorkEnumerationStrategy() != null) {
                checkAndInitializeStage(stage);
            }
        }

        // Re-seed processed graph hashes (global add-only set) if it was lost —
        // reseed from the history of every live campaign.
        if (redisQueueService.isProcessedGraphHashesEmpty()) {
            stages.stream()
                    .map(Stage::getCampaignId)
                    .distinct()
                    .forEach(this::reseedProcessedGraphHashes);
        }
    }

    private void checkAndInitializeStage(Stage stage) {
        // Check if already initialized
        if (redisQueueService.hasStageConfig(stage.getStageId())) {
            // Already initialized, do nothing
            return;
        }

        // Need to initialize - fetch graph and compute total pairs
        log.info("Active stage {} config missing in Redis. Re-initializing...", stage.getStageId());

        Graph graph = middlewareClient.getGraphById(stage.getBaseGraphId());
        if (graph == null) {
            log.error("Could not fetch base graph {} for stage {}", stage.getBaseGraphId(), stage.getStageId());
            return;
        }

        initializeRedisForStage(stage, graph);
    }

    /**
     * Re-seeds the processed_graph_hashes set from MySQL after Redis data loss.
     * Fetches the most recent INACTIVE stages (up to top-results-count) and adds
     * their base graph hashes, preventing the system from revisiting already-exhausted graphs.
     */
    private void reseedProcessedGraphHashes(Integer campaignId) {
        int count = ramseyConfig.getStage().getCyclePreventionGraphLookbackCount();
        log.info("Re-seeding processed_graph_hashes from last {} INACTIVE stages for campaign {}...", count, campaignId);

        List<Stage> recentStages = middlewareClient.getRecentStagesByCampaignIdAndStatus(
                campaignId, Stage.Status.INACTIVE, count);

        int seeded = 0;
        for (Stage stage : recentStages) {
            Graph graph = middlewareClient.getGraphById(stage.getBaseGraphId());
            if (graph != null && graph.getEdgeData() != null) {
                String hash = GraphHashUtil.computeHash(graph.getEdgeData());
                redisQueueService.addProcessedGraphHash(hash);
                seeded++;
            }
        }

        log.info("Re-seeded {} processed graph hashes from recent stage history", seeded);
    }

    private void initializeRedisForStage(Stage stage, Graph graph) {
        // Calculate total pairs (red edges * blue edges)
        long redCount = 0;
        long blueCount = 0;
        for (int i = 0; i < graph.getEdgeData().length(); i++) {
            if (graph.getEdgeData().charAt(i) == '1') {
                redCount++;
            } else {
                blueCount++;
            }
        }
        long calculatedTotalPairs = computeTotalWorkUnits(redCount, blueCount, stage.getWorkEnumerationStrategy());

        log.info("Initializing counter-based mode for stage {}: redEdges={}, blueEdges={}, totalPairs={}, strategy={}",
                stage.getStageId(), redCount, blueCount, calculatedTotalPairs, stage.getWorkEnumerationStrategy());

        // Initialize Redis with counter and stage config
        redisQueueService.initializeStageCounter(
                stage.getStageId(),
                stage.getBaseGraphId(),
                graph,
                calculatedTotalPairs,
                stage.getWorkEnumerationStrategy());
    }
}
