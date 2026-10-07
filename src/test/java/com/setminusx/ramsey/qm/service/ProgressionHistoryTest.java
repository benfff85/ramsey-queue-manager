package com.setminusx.ramsey.qm.service;

import com.setminusx.ramsey.qm.client.MiddlewareClient;
import com.setminusx.ramsey.qm.model.ProgressionPoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProgressionHistoryTest {

    private static final int CAMPAIGN = 10;

    /** A middleware over {@code visible}, paged like the real endpoint; the test grows the list. */
    private static MiddlewareClient middleware(List<ProgressionPoint> visible, AtomicInteger calls) {
        MiddlewareClient mw = mock(MiddlewareClient.class);
        when(mw.getProgressionPage(eq(CAMPAIGN), anyInt(), anyInt())).thenAnswer(inv -> {
            calls.incrementAndGet();
            int since = inv.getArgument(1);
            int limit = inv.getArgument(2);
            return visible.stream().filter(p -> p.getStageId() > since).limit(limit).toList();
        });
        return mw;
    }

    /**
     * Campaign-shaped: stage ids with gaps, descents broken by kicks that jump the count, counts
     * from a narrow band so ties are common, and the odd stage without a count.
     */
    private static List<ProgressionPoint> history(int n, long seed) {
        Random rng = new Random(seed);
        List<ProgressionPoint> out = new ArrayList<>(n);
        int stageId = 5_000;
        long count = 900;
        for (int i = 0; i < n; i++) {
            stageId += 1 + rng.nextInt(3);
            boolean kick = i > 0 && rng.nextInt(7_000) == 0;
            count = kick ? 600 + rng.nextInt(300) : Math.max(25, count - rng.nextInt(5) + 2);
            ProgressionPoint p = new ProgressionPoint();
            p.setStageId(stageId);
            p.setGraphId(stageId + 1_000_000);
            p.setCliqueCount(rng.nextInt(500) == 0 ? null : count);
            p.setDetails(kick ? "PERTURBATION kick from graph 1 (25), pairs=60, escalation=x1" : "source: EXHAUSTIVE");
            out.add(p);
        }
        return out;
    }

    // ---- the full-history computations the summary replaces, kept verbatim as the oracle ----

    private static ProgressionPoint minPointFrom(List<ProgressionPoint> history, int fromStageId) {
        return history.stream()
                .filter(p -> p.getCliqueCount() != null && p.getStageId() >= fromStageId)
                .min(Comparator.comparingLong(ProgressionPoint::getCliqueCount)
                        .thenComparing(ProgressionPoint::getStageId))
                .orElse(null);
    }

    private static List<Integer> kicks(List<ProgressionPoint> history) {
        return history.stream()
                .filter(p -> p.getDetails() != null && p.getDetails().startsWith("PERTURBATION"))
                .map(ProgressionPoint::getStageId)
                .sorted()
                .toList();
    }

    private static void assertSame(ProgressionPoint want, ProgressionHistory.Point got) {
        assertNotNull(want);
        assertNotNull(got);
        assertEquals(want.getStageId(), got.stageId());
        assertEquals(want.getGraphId(), got.graphId());
        assertEquals(want.getCliqueCount(), got.cliqueCount());
    }

    /** Every aggregate the perturbation check reads, against the full-history oracle. */
    private static void assertMatchesOracle(List<ProgressionPoint> seen, ProgressionHistory.Summary summary) {
        assertEquals(seen.size(), summary.size());
        ProgressionPoint incumbent = minPointFrom(seen, Integer.MIN_VALUE);
        assertSame(incumbent, summary.incumbent());
        long after = seen.stream().filter(p -> p.getStageId() > incumbent.getStageId()).count();
        assertEquals(after, summary.stagesAfter(summary.incumbent()));

        assertEquals(kicks(seen), summary.kickStageIds());
        for (int kick : kicks(seen)) {
            ProgressionPoint floor = minPointFrom(seen, kick);
            ProgressionHistory.Point got = summary.floorSince(kick);
            assertSame(floor, got);
            assertEquals(seen.stream().filter(p -> p.getStageId() > floor.getStageId()).count(),
                    summary.stagesAfter(got), "stages since the basin floor after kick " + kick);
        }
    }

    /**
     * The summary is built a refresh at a time, in arbitrary batches, as the check runs every
     * minute. After every refresh it must agree with the full-history computation over exactly
     * the stages seen so far.
     */
    @Test
    void everyAggregateMatchesTheFullHistoryAfterEveryRefresh() {
        List<ProgressionPoint> all = history(120_000, 1);
        assertTrue(kicks(all).size() >= 10, "the history needs kicks to be a real test");
        List<ProgressionPoint> visible = new ArrayList<>();
        ProgressionHistory history = new ProgressionHistory(middleware(visible, new AtomicInteger()));
        Random rng = new Random(2);
        while (visible.size() < all.size()) {
            int next = Math.min(all.size(), visible.size() + 1 + rng.nextInt(12_000));
            visible.addAll(all.subList(visible.size(), next));
            assertMatchesOracle(visible, history.refresh(CAMPAIGN));
        }
    }

    @Test
    void firstRefreshPagesUntilAShortPage() {
        List<ProgressionPoint> all = history(3 * ProgressionHistory.PAGE_SIZE, 3); // exact multiple
        AtomicInteger calls = new AtomicInteger();
        ProgressionHistory history = new ProgressionHistory(middleware(all, calls));

        assertMatchesOracle(all, history.refresh(CAMPAIGN));
        assertEquals(4, calls.get(), "three full pages, then an empty one");

        history.refresh(CAMPAIGN);
        assertEquals(5, calls.get(), "a caught-up refresh is one call");
    }

    /** Floors are kept per kick epoch, so a start inside an epoch cannot be answered exactly. */
    @Test
    void floorSinceRefusesAStageThatIsNotAKick() {
        List<ProgressionPoint> all = history(20_000, 4);
        ProgressionHistory.Summary summary =
                new ProgressionHistory(middleware(all, new AtomicInteger())).refresh(CAMPAIGN);
        int notAKick = all.get(10).getStageId();
        assertFalse(summary.kickStageIds().contains(notAKick));
        assertThrows(IllegalArgumentException.class, () -> summary.floorSince(notAKick));
    }

    /** A page that repeats stages already summarised must not count them twice. */
    @Test
    void overlappingPagesAreIgnored() {
        List<ProgressionPoint> all = history(1_000, 5);
        ProgressionHistory.Summary summary = new ProgressionHistory.Summary();
        all.subList(0, 600).forEach(summary::accept);
        all.subList(500, 1_000).forEach(summary::accept);
        assertMatchesOracle(all, summary);
    }
}
