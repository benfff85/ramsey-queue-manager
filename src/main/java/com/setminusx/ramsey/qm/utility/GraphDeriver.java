package com.setminusx.ramsey.qm.utility;

import com.setminusx.ramsey.qm.model.Edge;

import java.util.List;

/** Builds a derived graph's edge string locally (no middleware round trip) and decides snapshots. */
public final class GraphDeriver {

    private GraphDeriver() {
    }

    public static String derive(String edgeData, int vertexCount, List<Edge> edgesToFlip) {
        char[] chars = edgeData.toCharArray();
        for (Edge edge : edgesToFlip) {
            int i = GraphHashUtil.edgeIndex(edge.getVertexOne(), edge.getVertexTwo(), vertexCount);
            chars[i] = chars[i] == '1' ? '0' : '1';
        }
        return new String(chars);
    }

    /**
     * Whether a new graph must store its full edge data. Snapshots start every chain (the base has
     * no lineage), bound replay length (checkpoints), and keep every campaign record durable
     * without lineage (a count below the known incumbent, or no incumbent known yet).
     */
    public static boolean isSnapshot(Integer baseDepth, int checkpointInterval, Long knownIncumbent, long cliqueCount) {
        if (baseDepth == null || baseDepth + 1 >= checkpointInterval) {
            return true;
        }
        return knownIncumbent == null || cliqueCount < knownIncumbent;
    }
}
