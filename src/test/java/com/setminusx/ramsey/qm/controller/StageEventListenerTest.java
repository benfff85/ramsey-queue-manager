package com.setminusx.ramsey.qm.controller;

import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * The two worker events want opposite handling, and this is where that difference lives.
 *
 * <p>A new best is the weakest qualifying improvement so far, so it arms a settle window and waits
 * for a better one. A completed stage has had every unit in its work space evaluated and reported,
 * so its top-N set is final — waiting buys nothing and costs the fleet its entire tail.
 */
class StageEventListenerTest {

    @Test
    void aNewBestArmsTheSettleTimerRatherThanProgressingNow() {
        StageAdoptScheduler adopt = mock(StageAdoptScheduler.class);
        StageProgressionMonitor monitor = mock(StageProgressionMonitor.class);

        new StageEventListener(adopt, monitor).onBestResult("{\"stageId\":42,\"cliqueCount\":7}");

        verify(adopt).onNewBest(42);
        verify(monitor, never()).checkStageNow(anyInt());
    }

    @Test
    void aCompletedStageProgressesImmediatelyWithNoSettleWindow() {
        StageAdoptScheduler adopt = mock(StageAdoptScheduler.class);
        StageProgressionMonitor monitor = mock(StageProgressionMonitor.class);

        new StageEventListener(adopt, monitor).onStageComplete("{\"stageId\":42}");

        verify(monitor).checkStageNow(42);
        verify(adopt, never()).onNewBest(anyInt());
    }

    /**
     * Pub/sub carries whatever any client publishes. An unreadable payload must not be able to
     * advance a stage, and must not take the listener thread down either.
     */
    @Test
    void anUnreadablePayloadAdvancesNothing() {
        StageAdoptScheduler adopt = mock(StageAdoptScheduler.class);
        StageProgressionMonitor monitor = mock(StageProgressionMonitor.class);
        StageEventListener listener = new StageEventListener(adopt, monitor);

        listener.onStageComplete("not json");
        listener.onStageComplete("{\"stage\":42}");
        listener.onBestResult("{}");

        verify(monitor, never()).checkStageNow(anyInt());
        verify(adopt, never()).onNewBest(anyInt());
    }

    /**
     * Stage ids are past 900,000 on campaign 3 and keep climbing; the parse must not cap them.
     */
    @Test
    void largeStageIdsParseIntact() {
        StageAdoptScheduler adopt = mock(StageAdoptScheduler.class);
        StageProgressionMonitor monitor = mock(StageProgressionMonitor.class);

        new StageEventListener(adopt, monitor).onStageComplete("{\"stageId\":2000000000}");

        verify(monitor).checkStageNow(2000000000);
    }
}
