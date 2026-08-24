package com.setminusx.ramsey.qm.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the workers' pub/sub announcements into stage-progression actions.
 *
 * <p>The two events want opposite handling, which is the whole reason they are separate channels:
 *
 * <ul>
 *   <li><b>New best</b> — the first announced improvement for a stage is usually the weakest
 *       qualifying one, found milliseconds in. Adopting it commits to a small step, so this arms a
 *       settle window and lets the fleet turn up a better one.
 *   <li><b>Stage complete</b> — every unit in the work space has been evaluated <em>and</em>
 *       reported, so the top-N set is final and nothing better can arrive. A settle window here
 *       would buy no quality at all while the whole fleet sits idle with nothing left to claim, so
 *       this progresses immediately.
 * </ul>
 *
 * <p>Pub/sub is fire-and-forget with no delivery guarantee, which is fine: the scheduled
 * progression loop remains the fallback, so a dropped message costs latency, never correctness.
 */
@Slf4j
@Component
public class StageEventListener {

    /** Payloads are {"stageId":N,...}; only the stage id drives either action. */
    private static final Pattern STAGE_ID = Pattern.compile("\"stageId\"\\s*:\\s*(\\d+)");

    private final StageAdoptScheduler adoptScheduler;
    private final StageProgressionMonitor progressionMonitor;

    public StageEventListener(StageAdoptScheduler adoptScheduler, StageProgressionMonitor progressionMonitor) {
        this.adoptScheduler = adoptScheduler;
        this.progressionMonitor = progressionMonitor;
    }

    /** A worker announced a new best result for a stage. */
    public void onBestResult(String payload) {
        Integer stageId = stageId(payload);
        if (stageId == null) {
            log.warn("Unparseable best-result event: {}", payload);
            return;
        }
        adoptScheduler.onNewBest(stageId);
    }

    /** A worker reported the last outstanding work unit of a stage. */
    public void onStageComplete(String payload) {
        Integer stageId = stageId(payload);
        if (stageId == null) {
            log.warn("Unparseable stage-complete event: {}", payload);
            return;
        }
        log.debug("Stage {} announced complete; progressing without waiting for the next poll", stageId);
        progressionMonitor.checkStageNow(stageId);
    }

    private Integer stageId(String payload) {
        Matcher m = STAGE_ID.matcher(payload);
        if (!m.find()) {
            return null;
        }
        try {
            return Integer.valueOf(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
