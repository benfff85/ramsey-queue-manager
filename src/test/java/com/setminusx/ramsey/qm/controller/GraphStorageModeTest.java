package com.setminusx.ramsey.qm.controller;

import com.setminusx.ramsey.qm.client.MiddlewareClient;
import com.setminusx.ramsey.qm.config.RamseyConfig;
import com.setminusx.ramsey.qm.model.*;
import com.setminusx.ramsey.qm.service.RedisQueueService;
import com.setminusx.ramsey.qm.utility.GraphHashUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static com.setminusx.ramsey.qm.config.RamseyConfig.GraphStorage.Mode.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GraphStorageModeTest {

    private final MiddlewareClient mw = mock(MiddlewareClient.class);
    private final RedisQueueService redis = mock(RedisQueueService.class);

    private RamseyConfig config(RamseyConfig.GraphStorage.Mode mode) {
        RamseyConfig cfg = mock(RamseyConfig.class);
        RamseyConfig.Stage stage = new RamseyConfig.Stage(); // immediate progression on improvement = true
        stage.setDefaultWorkEnumerationStrategy("SEQUENTIAL_WITH_SINGLES");
        when(cfg.getStage()).thenReturn(stage);
        RamseyConfig.GraphStorage gs = new RamseyConfig.GraphStorage();
        gs.setMode(mode);
        when(cfg.getGraphStorage()).thenReturn(gs);
        when(cfg.getPerturbation()).thenReturn(new RamseyConfig.Perturbation());
        return cfg;
    }

    /** A monitor whose progression history already knows campaign 10's incumbent count. */
    private StageProgressionMonitor monitorWithKnownIncumbent(RamseyConfig.GraphStorage.Mode mode, long incumbent) {
        StageProgressionMonitor m = new StageProgressionMonitor(mw, redis, config(mode));
        ProgressionPoint p = new ProgressionPoint();
        p.setStageId(1);
        p.setGraphId(1);
        p.setCliqueCount(incumbent);
        p.setDetails("source: EXHAUSTIVE");
        when(mw.getProgressionPage(eq(10), anyInt(), anyInt())).thenReturn(List.of(p));
        m.refreshProgressionHistory(10);
        return m;
    }

    private static Stage stage(int stageId, int baseGraphId, Stage.Status status) {
        Stage s = new Stage();
        s.setStageId(stageId);
        s.setBaseGraphId(baseGraphId);
        s.setCampaignId(10);
        s.setStatus(status);
        s.setWorkEnumerationStrategy("SEQUENTIAL_WITH_SINGLES");
        return s;
    }

    private static Graph base(int id, Integer depth) {
        Graph g = new Graph();
        g.setGraphId(id);
        g.setVertexCount(5);
        g.setSubgraphSize(3);
        g.setCliqueCount(100);
        g.setEdgeData("0000000000");
        g.setLineageDepth(depth);
        return g;
    }

    private static BestResult flipZeroOne(int baseGraphId, int count) {
        Edge e = new Edge();
        e.setVertexOne(0);
        e.setVertexTwo(1);
        BestResult b = new BestResult();
        b.setBaseGraphId(baseGraphId);
        b.setCliqueCount(count);
        b.setEdgesToFlip(List.of(e));
        return b;
    }

    /**
     * Stage stageId (on graph baseId) has an improvement flipping edge 0:1 to `count` cliques. The
     * advance saves graph savedId and creates stage nextStageId. baseGraph == null: no GET stub, so
     * a GET of baseId returns null (the test asserts the cache made it unnecessary).
     */
    private void stub(int stageId, int baseId, Graph baseGraph, int savedId, int nextStageId, int count) {
        when(mw.getActiveStages()).thenReturn(List.of(stage(stageId, baseId, Stage.Status.ACTIVE)));
        if (baseGraph != null) {
            when(mw.getGraphById(baseId)).thenReturn(baseGraph);
        }
        when(redis.getTopResults(stageId, 1)).thenReturn(List.of(flipZeroOne(baseId, count)));
        when(redis.isGraphAlreadyProcessed(anyString())).thenReturn(false);
        // doAnswer().when(): re-stubbing via when(mw.createGraph(any())) would CALL the mock and run the
        // previous stub's answer with a null argument.
        doAnswer(inv -> {
            Graph posted = inv.getArgument(0);
            Graph saved = new Graph();             // the mw echoes exactly what it stored
            saved.setGraphId(savedId);
            saved.setVertexCount(posted.getVertexCount());
            saved.setSubgraphSize(posted.getSubgraphSize());
            saved.setCliqueCount(posted.getCliqueCount());
            saved.setEdgeData(posted.getEdgeData());
            saved.setLineageDepth(posted.getLineageDepth());
            return saved;
        }).when(mw).createGraph(any());
        when(mw.createStage(any())).thenReturn(stage(nextStageId, savedId, Stage.Status.INACTIVE));
    }

    private Graph lastPosted() {
        ArgumentCaptor<Graph> c = ArgumentCaptor.forClass(Graph.class);
        verify(mw, atLeastOnce()).createGraph(c.capture());
        return c.getValue();
    }

    @Test
    void offModeIsTodaysBehaviour() {
        Graph derived = base(0, null);
        derived.setEdgeData("1000000000");
        when(mw.getDerivedGraph(eq(7), anyString())).thenReturn(derived);
        stub(42, 7, base(7, null), 8, 43, 90);
        new StageProgressionMonitor(mw, redis, config(OFF)).checkForProgression();
        verify(mw).getDerivedGraph(7, "{{0:1}}");
        Graph posted = lastPosted();
        assertEquals("1000000000", posted.getEdgeData());
        assertNull(posted.getLineageDepth());
        assertNull(posted.getGraphHash());
    }

    @Test
    void shadowModeStoresFullBitsAndLineageWithoutTheDeriveCall() {
        stub(42, 7, base(7, 3), 8, 43, 90);                    // 90 > incumbent 50: not a record
        monitorWithKnownIncumbent(SHADOW, 50).checkForProgression();
        verify(mw, never()).getDerivedGraph(anyInt(), anyString());
        Graph posted = lastPosted();
        assertEquals("1000000000", posted.getEdgeData());
        assertEquals(7, posted.getParentGraphId());
        assertEquals("{{0:1}}", posted.getFlippedEdges());
        assertEquals(GraphHashUtil.computeHash("1000000000"), posted.getGraphHash());
        assertEquals(4, posted.getLineageDepth());
    }

    @Test
    void deltaModeSnapshotsWhenBaseHasNoLineage() {
        stub(42, 7, base(7, null), 8, 43, 90);
        monitorWithKnownIncumbent(DELTA, 50).checkForProgression();
        Graph posted = lastPosted();
        assertEquals(0, posted.getLineageDepth());
        assertEquals("1000000000", posted.getEdgeData());
    }

    @Test
    void deltaModeSnapshotsANewCampaignRecord() {
        stub(42, 7, base(7, 3), 8, 43, 40);                    // 40 < incumbent 50
        monitorWithKnownIncumbent(DELTA, 50).checkForProgression();
        assertEquals(0, lastPosted().getLineageDepth());
        assertEquals("1000000000", lastPosted().getEdgeData());
    }

    @Test
    void deltaModeAfterRestartUsesRebuiltBaseAndItsDepth() {
        stub(42, 7, base(7, 5), 8, 43, 90);                    // fresh monitor: graph 7 comes from GET
        monitorWithKnownIncumbent(DELTA, 50).checkForProgression();
        verify(mw).getGraphById(7);
        Graph posted = lastPosted();
        assertEquals(7, posted.getParentGraphId());
        assertEquals(6, posted.getLineageDepth());
        assertNull(posted.getEdgeData(), "an ordinary delta graph stores no edge data");
    }

    @Test
    void redisAlwaysGetsTheFullDerivedBits() {
        stub(42, 7, base(7, 3), 8, 43, 90);                    // ordinary delta: the POST carries no bits
        monitorWithKnownIncumbent(DELTA, 50).checkForProgression();
        ArgumentCaptor<Graph> g = ArgumentCaptor.forClass(Graph.class);
        verify(redis).initializeStageCounter(eq(43), eq(8), g.capture(), anyLong(), anyString());
        assertEquals("1000000000", g.getValue().getEdgeData());
    }

    @Test
    void secondAdvanceUsesTheCachedBaseInsteadOfAGet() {
        StageProgressionMonitor monitor = monitorWithKnownIncumbent(DELTA, 50);
        stub(42, 7, base(7, 3), 8, 43, 90);
        monitor.checkForProgression();                         // 42 -> 43 on new graph 8, cached
        stub(43, 8, null, 9, 44, 80);                          // graph 8 deliberately not GET-able
        clearInvocations(mw);
        monitor.checkForProgression();                         // 43 -> 44
        verify(mw, never()).getGraphById(8);
        Graph posted = lastPosted();
        assertEquals(8, posted.getParentGraphId());
        assertEquals(5, posted.getLineageDepth());
    }

    @Test
    void reseedPrefersTheStoredHash() {
        RamseyConfig cfg = config(DELTA);
        cfg.getStage().setCyclePreventionGraphLookbackCount(1);
        when(mw.getActiveStages()).thenReturn(List.of(stage(42, 7, Stage.Status.ACTIVE)));
        when(redis.hasStageConfig(42)).thenReturn(true);
        when(redis.isProcessedGraphHashesEmpty()).thenReturn(true);
        when(mw.getRecentStagesByCampaignIdAndStatus(10, Stage.Status.INACTIVE, 1))
                .thenReturn(List.of(stage(41, 6, Stage.Status.INACTIVE)));
        Graph stored = new Graph();
        stored.setGraphId(6);
        stored.setGraphHash("feed");
        when(mw.getStoredGraph(6)).thenReturn(stored);

        new StageProgressionMonitor(mw, redis, cfg).ensureActiveStageInitialized();

        verify(redis).addProcessedGraphHash("feed");
        verify(mw, never()).getGraphById(6);
    }
}
