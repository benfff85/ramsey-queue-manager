package com.setminusx.ramsey.qm.utility;

import com.setminusx.ramsey.qm.model.Edge;
import com.setminusx.ramsey.qm.model.Graph;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class GraphDeriverTest {

    private static Edge e(int a, int b) {
        Edge x = new Edge();
        x.setVertexOne(a);
        x.setVertexTwo(b);
        return x;
    }

    /** The middleware's deriveGraph index: a*(2n-a-1)/2 + (b-a-1). Must agree for every edge. */
    @Test
    void indexMatchesTheMiddlewareForEveryEdgeOf282() {
        int n = 282;
        for (int a = 0; a < n; a++) {
            for (int b = a + 1; b < n; b++) {
                assertEquals(a * (2 * n - a - 1) / 2 + (b - a - 1), GraphHashUtil.edgeIndex(a, b, n));
                assertEquals(GraphHashUtil.edgeIndex(a, b, n), GraphHashUtil.edgeIndex(b, a, n));
            }
        }
    }

    @Test
    void deriveFlipsExactlyTheListedEdges() {
        assertEquals("1000000001", GraphDeriver.derive("0000000000", 5, List.of(e(0, 1), e(3, 4))));
        assertEquals("0000000000", GraphDeriver.derive("0000000000", 5, List.of(e(1, 3), e(3, 1))));
    }

    /** The hash the QM records for novelty must be the hash of the graph it stores. */
    @Test
    void derivedHashEqualsHashOfDerivedBits() {
        Random rng = new Random(3);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 39_621; i++) sb.append(rng.nextBoolean() ? '1' : '0');
        Graph base = new Graph();
        base.setEdgeData(sb.toString());
        base.setVertexCount(282);
        List<Edge> flips = List.of(e(23, 281), e(193, 281));
        assertEquals(GraphHashUtil.computeHash(GraphDeriver.derive(sb.toString(), 282, flips)),
                GraphHashUtil.computeDerivedGraphHash(base, flips));
    }

    @Test
    void snapshotRules() {
        // base predates lineage -> every chain starts from a stored graph
        assertTrue(GraphDeriver.isSnapshot(null, 1000, 25_000L, 26_000));
        // checkpoint: the next depth would reach the interval
        assertTrue(GraphDeriver.isSnapshot(999, 1000, 25_000L, 26_000));
        assertFalse(GraphDeriver.isSnapshot(998, 1000, 25_000L, 26_000));
        // new campaign incumbent, or incumbent unknown (QM just started)
        assertTrue(GraphDeriver.isSnapshot(5, 1000, 25_439L, 25_438));
        assertTrue(GraphDeriver.isSnapshot(5, 1000, null, 26_000));
        // ties are not new records
        assertFalse(GraphDeriver.isSnapshot(5, 1000, 25_439L, 25_439));
    }
}
