package com.setminusx.ramsey.qm.controller;

import com.setminusx.ramsey.qm.client.MiddlewareClient;
import com.setminusx.ramsey.qm.config.RamseyConfig;
import com.setminusx.ramsey.qm.model.BestResult;
import com.setminusx.ramsey.qm.model.Graph;
import com.setminusx.ramsey.qm.model.Stage;
import com.setminusx.ramsey.qm.service.RedisQueueService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StageProgressionMonitorTest {

    @Test
    void pairStrategiesCountRedTimesBlue() {
        assertEquals(19810L * 19811L,
                StageProgressionMonitor.computeTotalWorkUnits(19810, 19811, "DUAL_EDGE_CARDINALITY"));
        assertEquals(19810L * 19811L,
                StageProgressionMonitor.computeTotalWorkUnits(19810, 19811, "BASIC"));
    }

    @Test
    void singlesStrategyAddsBothColorCounts() {
        // Stage 8319 shape: 19,810 red / 19,811 blue -> 39,621 singles.
        assertEquals(39621L + 19810L * 19811L,
                StageProgressionMonitor.computeTotalWorkUnits(19810, 19811, "DUAL_EDGE_CARDINALITY_WITH_SINGLES"));
        // Mirrored balance gives the identical total.
        assertEquals(39621L + 19811L * 19810L,
                StageProgressionMonitor.computeTotalWorkUnits(19811, 19810, "DUAL_EDGE_CARDINALITY_WITH_SINGLES"));
    }

    @Test
    void singlesStrategyCountsBothColorsWhenTied() {
        assertEquals(10L + 25L,
                StageProgressionMonitor.computeTotalWorkUnits(5, 5, "DUAL_EDGE_CARDINALITY_WITH_SINGLES"));
    }

    @Test
    void nullStrategyFallsBackToPairs() {
        assertEquals(25L, StageProgressionMonitor.computeTotalWorkUnits(5, 5, null));
    }

    @Test
    void fullyProcessedWhenProcessedReachesTotal() {
        long total = 39621L + 19812L * 19809L; // a real WITH_SINGLES total
        assertTrue(StageProgressionMonitor.isFullyProcessed(total, total));
        // Final partial batch can push processed slightly past total.
        assertTrue(StageProgressionMonitor.isFullyProcessed(total + 250, total));
    }

    @Test
    void notFullyProcessedWhileStragglersOutstanding() {
        long total = 392_495_529L;
        assertFalse(StageProgressionMonitor.isFullyProcessed(total - 1, total));
        assertFalse(StageProgressionMonitor.isFullyProcessed(0L, total));
    }

    @Test
    void notFullyProcessedWhenTotalUnknown() {
        // getStageTotalPairs returns -1 when config is missing; 0 guards uninitialized stages.
        assertFalse(StageProgressionMonitor.isFullyProcessed(100L, -1L));
        assertFalse(StageProgressionMonitor.isFullyProcessed(100L, 0L));
    }

    @Test
    void doesNotProgressOnImprovementWhenImmediateProgressionDisabled() {
        MiddlewareClient mw = mock(MiddlewareClient.class);
        RedisQueueService redis = mock(RedisQueueService.class);
        RamseyConfig config = mock(RamseyConfig.class);

        RamseyConfig.Stage stageCfg = new RamseyConfig.Stage();
        stageCfg.setImmediatelyProgressOnImprovement(false); // run to exhaustion
        when(config.getStage()).thenReturn(stageCfg);
        when(config.getCampaignId()).thenReturn(10);

        when(mw.getStagesByCampaignIdAndStatus(10, Stage.Status.ACTIVE)).thenReturn(List.of(activeStage()));
        when(mw.getGraphById(7)).thenReturn(baseGraph(100));
        when(redis.getTopResults(42, 1)).thenReturn(List.of(improvement(90))); // beats base (100)
        when(redis.isStageExhausted(42)).thenReturn(false);

        new StageProgressionMonitor(mw, redis, config).checkForProgression();

        // Improvement exists, but immediate progression is off and the stage isn't exhausted.
        verify(mw, never()).createStage(any());
        verify(mw, never()).createGraph(any());
    }

    @Test
    void progressesOnImprovementWhenImmediateProgressionEnabled() {
        MiddlewareClient mw = mock(MiddlewareClient.class);
        RedisQueueService redis = mock(RedisQueueService.class);
        RamseyConfig config = mock(RamseyConfig.class);

        RamseyConfig.Stage stageCfg = new RamseyConfig.Stage(); // default: immediate progression = true
        when(config.getStage()).thenReturn(stageCfg);
        when(config.getCampaignId()).thenReturn(10);

        when(mw.getStagesByCampaignIdAndStatus(10, Stage.Status.ACTIVE)).thenReturn(List.of(activeStage()));
        when(mw.getGraphById(7)).thenReturn(baseGraph(100));
        when(redis.getTopResults(42, 1)).thenReturn(List.of(improvement(90)));
        when(redis.isGraphAlreadyProcessed(anyString())).thenReturn(false);

        Graph saved = new Graph();
        saved.setGraphId(8);
        when(mw.createGraph(any())).thenReturn(saved);
        Stage created = new Stage();
        created.setStageId(43); // null strategy -> skips Redis re-init
        when(mw.createStage(any())).thenReturn(created);

        new StageProgressionMonitor(mw, redis, config).checkForProgression();

        verify(mw).createStage(any()); // progressed
    }

    private static Stage activeStage() {
        Stage s = new Stage();
        s.setStageId(42);
        s.setBaseGraphId(7);
        s.setStatus(Stage.Status.ACTIVE);
        s.setCampaignId(10);
        return s;
    }

    private static Graph baseGraph(int cliqueCount) {
        Graph g = new Graph();
        g.setGraphId(7);
        g.setCliqueCount(cliqueCount);
        g.setVertexCount(282);
        g.setSubgraphSize(8);
        return g;
    }

    private static BestResult improvement(int cliqueCount) {
        BestResult b = new BestResult();
        b.setCliqueCount(cliqueCount);
        b.setBaseGraphId(7);
        b.setGraphBitstring("0101"); // SA-style result -> simple hash path
        return b;
    }
}
