package com.setminusx.ramsey.qm.controller;

import com.setminusx.ramsey.qm.client.MiddlewareClient;
import com.setminusx.ramsey.qm.config.RamseyConfig;
import com.setminusx.ramsey.qm.model.Graph;
import com.setminusx.ramsey.qm.model.ProgressionPoint;
import com.setminusx.ramsey.qm.model.Stage;
import com.setminusx.ramsey.qm.service.RedisQueueService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PerturbationTest {

    // ---------- perturbBalanced (pure) ----------

    @Test
    void perturbBalanced_preservesRedBlueCounts() {
        String bits = "1".repeat(20) + "0".repeat(25);
        String kicked = StageProgressionMonitor.perturbBalanced(bits, 7, new Random(1));
        assertEquals(count(bits, '1'), count(kicked, '1'));
        assertEquals(count(bits, '0'), count(kicked, '0'));
        assertNotEquals(bits, kicked);
        // exactly 2*pairs positions differ
        int diff = 0;
        for (int i = 0; i < bits.length(); i++) {
            if (bits.charAt(i) != kicked.charAt(i)) diff++;
        }
        assertEquals(14, diff);
    }

    @Test
    void perturbBalanced_capsAtAvailableEdges() {
        String bits = "10"; // 1 red, 1 blue
        String kicked = StageProgressionMonitor.perturbBalanced(bits, 99, new Random(1));
        assertEquals(1, count(kicked, '1'));
    }

    // ---------- wall detection / kick behavior ----------

    private RamseyConfig config(boolean enabled, int basinStaleStages) {
        RamseyConfig config = mock(RamseyConfig.class);
        RamseyConfig.Perturbation p = new RamseyConfig.Perturbation();
        p.setEnabled(enabled);
        p.setBasinStaleStages(basinStaleStages);
        p.setEdgePairs(2);
        lenient().when(config.getPerturbation()).thenReturn(p);
        RamseyConfig.Stage stageCfg = new RamseyConfig.Stage();
        lenient().when(config.getStage()).thenReturn(stageCfg);
        return config;
    }

    private Stage activeStage(int stageId, int campaignId) {
        Stage s = new Stage();
        s.setStageId(stageId);
        s.setBaseGraphId(stageId);
        s.setCampaignId(campaignId);
        s.setStatus(Stage.Status.ACTIVE);
        return s;
    }

    /** history: min at minStage, then wallLength stages after it. */
    private List<ProgressionPoint> walledHistory(int minStageId, long minClique, int wallLength) {
        List<ProgressionPoint> h = new ArrayList<>();
        for (int i = 1; i < minStageId; i++) {
            h.add(point(i, minClique + 100 + i));
        }
        h.add(point(minStageId, minClique));
        for (int i = 1; i <= wallLength; i++) {
            h.add(point(minStageId + i, minClique + 50));
        }
        return h;
    }

    private ProgressionPoint point(int stageId, long clique) {
        ProgressionPoint p = new ProgressionPoint();
        p.setStageId(stageId);
        p.setGraphId(stageId);
        p.setCliqueCount(clique);
        return p;
    }

    @Test
    void disabled_doesNothing() {
        MiddlewareClient mw = mock(MiddlewareClient.class);
        RedisQueueService redis = mock(RedisQueueService.class);
        new StageProgressionMonitor(mw, redis, config(false, 500)).checkForPerturbation();
        verifyNoInteractions(mw);
    }

    @Test
    void notWalled_noKick() {
        MiddlewareClient mw = mock(MiddlewareClient.class);
        RedisQueueService redis = mock(RedisQueueService.class);
        when(mw.getActiveStages()).thenReturn(List.of(activeStage(600, 3)));
        when(mw.getProgression(3)).thenReturn(walledHistory(400, 1000, 100)); // wall only 100 < 500

        new StageProgressionMonitor(mw, redis, config(true, 500)).checkForPerturbation();

        verify(mw, never()).createGraph(any());
        verify(mw, never()).createStage(any());
    }

    @Test
    void walled_kicksFromIncumbentMinGraph() {
        MiddlewareClient mw = mock(MiddlewareClient.class);
        RedisQueueService redis = mock(RedisQueueService.class);
        Stage current = activeStage(1000, 3);
        when(mw.getActiveStages()).thenReturn(List.of(current));
        when(mw.getProgression(3)).thenReturn(walledHistory(400, 1000, 600)); // wall 600 >= 500

        // incumbent = min stage's base graph (id 400), K5 bitstring for a real count
        Graph incumbent = new Graph();
        incumbent.setGraphId(400);
        incumbent.setEdgeData("1".repeat(10));
        incumbent.setVertexCount(5);
        incumbent.setSubgraphSize(5);
        incumbent.setCliqueCount(1);
        when(mw.getGraphById(400)).thenReturn(incumbent);

        when(redis.isGraphAlreadyProcessed(anyString())).thenReturn(false);
        Graph saved = new Graph();
        saved.setGraphId(9000);
        when(mw.createGraph(any())).thenReturn(saved);
        Stage created = new Stage();
        created.setStageId(1001); // null strategy -> skip redis init
        when(mw.createStage(any())).thenReturn(created);

        new StageProgressionMonitor(mw, redis, config(true, 500)).checkForPerturbation();

        // kicked from graph 400 (the incumbent), NOT the current stage's base (1000)
        verify(mw).getGraphById(400);
        verify(mw).createGraph(any());
        verify(mw).createStage(any());
        verify(redis).addProcessedGraphHash(anyString());
        // old stage deactivated
        assertEquals(Stage.Status.INACTIVE, current.getStatus());
    }

    @Test
    void walled_butAllKicksVisited_givesUpGracefully() {
        MiddlewareClient mw = mock(MiddlewareClient.class);
        RedisQueueService redis = mock(RedisQueueService.class);
        when(mw.getActiveStages()).thenReturn(List.of(activeStage(1000, 3)));
        when(mw.getProgression(3)).thenReturn(walledHistory(400, 1000, 600));
        Graph incumbent = new Graph();
        incumbent.setGraphId(400);
        incumbent.setEdgeData("1".repeat(10));
        incumbent.setVertexCount(5);
        incumbent.setSubgraphSize(5);
        when(mw.getGraphById(400)).thenReturn(incumbent);
        when(redis.isGraphAlreadyProcessed(anyString())).thenReturn(true); // everything visited

        new StageProgressionMonitor(mw, redis, config(true, 500)).checkForPerturbation();

        verify(mw, never()).createGraph(any());
        verify(mw, never()).createStage(any());
    }

    /** A progression point tagged as a perturbation kick (how kicks are recovered on restart). */
    private ProgressionPoint kickPoint(int stageId, long clique) {
        ProgressionPoint p = point(stageId, clique);
        p.setDetails("PERTURBATION kick from graph 100 (1000), pairs=60, escalation=x1");
        return p;
    }

    @Test
    void restart_recentKick_hydratesFromHistory_doesNotReKickEarly() {
        MiddlewareClient mw = mock(MiddlewareClient.class);
        RedisQueueService redis = mock(RedisQueueService.class);
        when(mw.getActiveStages()).thenReturn(List.of(activeStage(700, 3)));

        // Fresh QM (no in-memory kick state). History: min 1000 @ stage 100, then walled,
        // with a KICK (details-tagged) at stage 650 — only 50 stages ago, i.e. < wallStages
        // 500. The old code (lastKick == null) would skip the gate and re-kick; hydration
        // must recover lastKick=650 and hold.
        List<ProgressionPoint> h = new ArrayList<>();
        for (int i = 1; i < 100; i++) h.add(point(i, 1100 + i));
        h.add(point(100, 1000)); // min
        for (int i = 101; i <= 700; i++) h.add(i == 650 ? kickPoint(i, 1050) : point(i, 1050));
        when(mw.getProgression(3)).thenReturn(h);

        new StageProgressionMonitor(mw, redis, config(true, 500)).checkForPerturbation();

        verify(mw, never()).createGraph(any()); // no early re-kick
    }

    @Test
    void restart_oldKick_hydratesFromHistory_stillKicksWhenWallElapsed() {
        MiddlewareClient mw = mock(MiddlewareClient.class);
        RedisQueueService redis = mock(RedisQueueService.class);
        Stage current = activeStage(700, 3);
        when(mw.getActiveStages()).thenReturn(List.of(current));

        // Same shape but the kick is at stage 120 — 580 stages ago (>= 500), so the wall
        // since the recovered kick HAS elapsed and a (legitimate) kick should fire.
        List<ProgressionPoint> h = new ArrayList<>();
        for (int i = 1; i < 100; i++) h.add(point(i, 1100 + i));
        h.add(point(100, 1000)); // min
        for (int i = 101; i <= 700; i++) h.add(i == 120 ? kickPoint(i, 1050) : point(i, 1050));
        when(mw.getProgression(3)).thenReturn(h);
        Graph incumbent = new Graph();
        incumbent.setGraphId(100);
        incumbent.setEdgeData("1".repeat(10));
        incumbent.setVertexCount(5);
        incumbent.setSubgraphSize(5);
        when(mw.getGraphById(100)).thenReturn(incumbent);
        when(redis.isGraphAlreadyProcessed(anyString())).thenReturn(false);
        Graph saved = new Graph();
        saved.setGraphId(9000);
        when(mw.createGraph(any())).thenReturn(saved);
        Stage created = new Stage();
        created.setStageId(701);
        when(mw.createStage(any())).thenReturn(created);

        new StageProgressionMonitor(mw, redis, config(true, 500)).checkForPerturbation();

        verify(mw).createGraph(any()); // wall elapsed since recovered kick -> kicks
    }

    /**
     * Campaign incumbent at stage 100, a details-tagged kick at {@code kickStage}, then one basin
     * point per stage with counts from {@code basinCount} (indexed 1..basinLen). The kick point
     * itself is a big spike, as a real perturbed graph always is.
     */
    private List<ProgressionPoint> kickedHistory(
            long campaignMin, int kickStage, java.util.function.IntToLongFunction basinCount, int basinLen) {
        List<ProgressionPoint> h = new ArrayList<>();
        for (int i = 1; i < 100; i++) h.add(point(i, campaignMin + 1000 + i));
        h.add(point(100, campaignMin)); // campaign incumbent
        for (int i = 101; i < kickStage; i++) h.add(point(i, campaignMin + 50));
        h.add(kickPoint(kickStage, 5_000_000));
        for (int i = 1; i <= basinLen; i++) h.add(point(kickStage + i, basinCount.applyAsLong(i)));
        return h;
    }

    private void stubKickTargets(MiddlewareClient mw, RedisQueueService redis, int incumbentGraphId) {
        Graph incumbent = new Graph();
        incumbent.setGraphId(incumbentGraphId);
        incumbent.setEdgeData("1".repeat(10));
        incumbent.setVertexCount(5);
        incumbent.setSubgraphSize(5);
        incumbent.setCliqueCount(1);
        lenient().when(mw.getGraphById(incumbentGraphId)).thenReturn(incumbent);
        lenient().when(redis.isGraphAlreadyProcessed(anyString())).thenReturn(false);
        Graph saved = new Graph();
        saved.setGraphId(9000);
        lenient().when(mw.createGraph(any())).thenReturn(saved);
        Stage created = new Stage();
        created.setStageId(99999);
        lenient().when(mw.createStage(any())).thenReturn(created);
    }

    /**
     * The staleness clock runs from the BASIN FLOOR, not from the kick, so a descent that is
     * still finding new minima is never interrupted — however long it runs. Under the old fixed
     * stages-since-kick wall this basin (1,800 stages past its kick, still in free fall) would
     * have been cut off; that is exactly how campaign 10's kicks 18/19/20 died, each bottoming
     * out on its FINAL stage at 58k/158k/81k against a 25,758 incumbent.
     */
    @Test
    void basinStillDescending_isNeverKicked_howeverLongTheDescentRuns() {
        MiddlewareClient mw = mock(MiddlewareClient.class);
        RedisQueueService redis = mock(RedisQueueService.class);
        when(mw.getActiveStages()).thenReturn(List.of(activeStage(2000, 3)));
        when(mw.getProgression(3)).thenReturn(kickedHistory(1000, 200, i -> 50_000 - i * 10L, 1800));
        stubKickTargets(mw, redis, 100);

        new StageProgressionMonitor(mw, redis, config(true, 500)).checkForPerturbation();

        verify(mw, never()).createGraph(any());
        verify(mw, never()).createStage(any());
    }

    /**
     * Mirror image: once the floor stops moving the basin is kicked after exactly
     * basinStaleStages, without sitting out the remainder of a long fixed wall.
     */
    @Test
    void basinFlattened_kicksOnceTheFloorStopsMoving() {
        MiddlewareClient mw = mock(MiddlewareClient.class);
        RedisQueueService redis = mock(RedisQueueService.class);
        when(mw.getActiveStages()).thenReturn(List.of(activeStage(2000, 3)));
        // Descends for 50 stages to a floor of 49,500 at stage 250, then 500 flat stages.
        when(mw.getProgression(3)).thenReturn(
                kickedHistory(1000, 200, i -> i <= 50 ? 50_000 - i * 10L : 49_500L, 550));
        stubKickTargets(mw, redis, 100);

        new StageProgressionMonitor(mw, redis, config(true, 500)).checkForPerturbation();

        verify(mw).getGraphById(100); // kicks the campaign incumbent, not the basin floor graph
        verify(mw).createGraph(any());
        verify(mw).createStage(any());
    }

    /** One improvement inside the stale window restarts the clock. */
    @Test
    void basinImprovingIntermittently_holdsTheKickOff() {
        MiddlewareClient mw = mock(MiddlewareClient.class);
        RedisQueueService redis = mock(RedisQueueService.class);
        when(mw.getActiveStages()).thenReturn(List.of(activeStage(2000, 3)));
        // Flat except for a single new floor 400 stages in — only 150 stages of staleness since.
        when(mw.getProgression(3)).thenReturn(
                kickedHistory(1000, 200, i -> i == 400 ? 49_000L : 49_500L, 550));
        stubKickTargets(mw, redis, 100);

        new StageProgressionMonitor(mw, redis, config(true, 500)).checkForPerturbation();

        verify(mw, never()).createGraph(any());
    }

    /**
     * Regression: the perturbation loop snapshots active stages before taking the campaign lock.
     * At sub-second stage cadence, the progression event can advance that snapshot while the kick
     * waits for the lock. Once inside the lock, the kick must rebind to the campaign's fresh ACTIVE
     * stage and replace that stage exactly once — using the stale snapshot either loses every kick
     * or, without the final active guard, creates a competing lineage.
     */
    @Test
    void staleSnapshot_rebindsToCurrentStageAndAppliesKickOnce() {
        MiddlewareClient mw = mock(MiddlewareClient.class);
        RedisQueueService redis = mock(RedisQueueService.class);
        Stage stale = activeStage(1000, 3);
        Stage current = activeStage(1001, 3);
        // The loop's snapshot still contains stage 1000, but upstream it is gone (advanced to 1001).
        when(mw.getActiveStages())
                .thenReturn(List.of(stale))
                .thenReturn(List.of(current));
        when(mw.getProgression(3)).thenReturn(walledHistory(400, 1000, 600));
        Graph incumbent = new Graph();
        incumbent.setGraphId(400);
        incumbent.setEdgeData("1".repeat(10));
        incumbent.setVertexCount(5);
        incumbent.setSubgraphSize(5);
        when(mw.getGraphById(400)).thenReturn(incumbent);
        when(redis.isGraphAlreadyProcessed(anyString())).thenReturn(false);
        Graph saved = new Graph();
        saved.setGraphId(9000);
        when(mw.createGraph(any())).thenReturn(saved);
        Stage created = new Stage();
        created.setStageId(1002);
        when(mw.createStage(any())).thenReturn(created);

        new StageProgressionMonitor(mw, redis, config(true, 500)).checkForPerturbation();

        assertEquals(Stage.Status.ACTIVE, stale.getStatus(), "the stale snapshot is never mutated");
        assertEquals(Stage.Status.INACTIVE, current.getStatus(), "the fresh active stage is replaced");
        verify(mw, never()).updateStage(same(stale));
        verify(mw).updateStage(same(current));
        verify(mw).createStage(any());
    }

    private static int count(String s, char c) {
        return (int) s.chars().filter(x -> x == c).count();
    }
}
