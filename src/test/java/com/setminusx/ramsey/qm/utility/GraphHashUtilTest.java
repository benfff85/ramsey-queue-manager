package com.setminusx.ramsey.qm.utility;

import com.setminusx.ramsey.qm.model.Edge;
import com.setminusx.ramsey.qm.model.Graph;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cross-language parity vectors shared with the Rust worker
 * (ramsey-worker-rust, src/hash.rs). These MUST stay identical on both sides —
 * the worker filters visited graphs by reproducing these hashes, so any drift
 * silently breaks cycle prevention. The expected values were computed
 * independently (Python hashlib over the same bytes).
 */
class GraphHashUtilTest {

    private static Graph graph(String edgeData, int vertexCount) {
        Graph g = new Graph();
        g.setEdgeData(edgeData);
        g.setVertexCount(vertexCount);
        return g;
    }

    private static Edge edge(int a, int b) {
        return Edge.builder().vertexOne(a).vertexTwo(b).build();
    }

    @Test
    void computeHashMatchesSharedVector() {
        assertEquals(
                "179b1a3e9e319cff0ca6671e07a62dad7d087b0f2a89eef0202612c3cb46baf9",
                GraphHashUtil.computeHash("1010101010"));
    }

    // 5-vertex base "1010101010"; flipping edge (1,3) toggles upper-triangular
    // index 5 -> "1010111010".
    @Test
    void derivedHashSingleEdgeFlipMatchesSharedVector() {
        assertEquals(
                "a883b77243b9e63ca15f5399b50849ab6a73edd8d574687d8aebd8d371a94493",
                GraphHashUtil.computeDerivedGraphHash(graph("1010101010", 5), List.of(edge(1, 3))));
    }

    // Flips (1,3) -> index 5 and (0,4) -> index 3 -> "1011111010".
    @Test
    void derivedHashMultiEdgeFlipMatchesSharedVector() {
        assertEquals(
                "2693da8118a62cd8cd0e1d7c88aa9b4d204981bfe11c8727574164a01b05dee9",
                GraphHashUtil.computeDerivedGraphHash(
                        graph("1010101010", 5), List.of(edge(1, 3), edge(0, 4))));
    }
}
