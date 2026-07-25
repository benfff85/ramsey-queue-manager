package com.setminusx.ramsey.qm.controller;

import com.setminusx.ramsey.qm.config.RamseyConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Arms a per-stage "settle timer" when a worker announces the first new best for that stage.
 *
 * <p>Why a timer at all, given we now get a pub/sub event? Because the first announced best is
 * usually the <em>weakest</em> qualifying improvement — some worker found a flip removing a few
 * hundred cliques milliseconds into the stage. Adopting on that event commits to a small step.
 * Waiting lets the fleet turn up a better one. Measured: shortening the adopt latency from
 * ~1000ms to ~200ms produced +28% stages but -31% cliques/stage, a net loss.
 *
 * <p>So the event and the wait do different jobs: pub/sub removes the random discovery lag (with
 * plain polling the settle time is uniform 0..pollInterval AND the poll interval floors the stage
 * duration), while the timer deliberately buys step quality. Together they give the settle window
 * that measured best at roughly half the cycle time.
 *
 * <p>Only the first event per stage arms the timer — workers publish on every record-break, which
 * is many times per stage. The arm is released when the timer fires, so a stage that wasn't
 * advanced (e.g. the candidate turned out to be already visited) can be re-armed by a later event.
 */
@Slf4j
@Component
public class StageAdoptScheduler {

    private final TaskScheduler taskScheduler;
    private final StageProgressionMonitor progressionMonitor;
    private final RamseyConfig ramseyConfig;

    /** Stages with a settle timer in flight, so repeat announcements don't pile up timers. */
    private final Map<Integer, Boolean> armed = new ConcurrentHashMap<>();

    public StageAdoptScheduler(
            TaskScheduler taskScheduler,
            StageProgressionMonitor progressionMonitor,
            RamseyConfig ramseyConfig) {
        this.taskScheduler = taskScheduler;
        this.progressionMonitor = progressionMonitor;
        this.ramseyConfig = ramseyConfig;
    }

    /** A worker announced a new best for this stage. */
    public void onNewBest(int stageId) {
        long settleMs = ramseyConfig.getStage().getAdoptSettleMs();
        if (settleMs <= 0) {
            return; // timer disabled: the polling loop remains the only trigger
        }
        if (armed.putIfAbsent(stageId, Boolean.TRUE) != null) {
            return; // already waiting on this stage
        }
        log.debug("Adopt settle timer armed for stage {} (+{}ms)", stageId, settleMs);
        taskScheduler.schedule(() -> fire(stageId), Instant.now().plusMillis(settleMs));
    }

    private void fire(int stageId) {
        try {
            progressionMonitor.adoptAfterSettle(stageId);
        } catch (Exception e) {
            log.warn("Adopt-after-settle failed for stage {}: {}", stageId, e.toString());
        } finally {
            // Release so a later announcement can re-arm (needed near the floor, where the first
            // improvement may be rejected and the next one arrives seconds later).
            armed.remove(stageId);
        }
    }
}
