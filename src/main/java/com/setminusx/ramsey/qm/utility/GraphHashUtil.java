package com.setminusx.ramsey.qm.utility;

import com.setminusx.ramsey.qm.model.Edge;
import com.setminusx.ramsey.qm.model.Graph;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Utility for computing graph hashes.
 * Used to track which graphs have been processed to avoid revisiting them.
 */
public class GraphHashUtil {

    private static final HexFormat HEX_FORMAT = HexFormat.of();

    /**
     * Compute SHA-256 hash of edge data string.
     * This uniquely identifies a graph's edge configuration.
     */
    public static String computeHash(String edgeData) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(edgeData.getBytes(StandardCharsets.UTF_8));
            return HEX_FORMAT.formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Compute the hash of a derived graph (base graph with edges flipped).
     * This computes the hash of what the graph WOULD be after flipping edges,
     * without actually constructing the full edge data string.
     */
    public static String computeDerivedGraphHash(Graph baseGraph, List<Edge> edgesToFlip) {
        String edgeData = baseGraph.getEdgeData();
        int vertexCount = baseGraph.getVertexCount();

        // Convert to mutable char array
        char[] chars = edgeData.toCharArray();

        // Flip the specified edges
        for (Edge edge : edgesToFlip) {
            int index = getEdgeIndex(edge.getVertexOne(), edge.getVertexTwo(), vertexCount);
            chars[index] = chars[index] == '1' ? '0' : '1';
        }

        return computeHash(new String(chars));
    }

    /**
     * Calculate edge index in the bitstring representation.
     * Uses upper triangular matrix indexing: index = v1 * (n-1) - v1*(v1+1)/2 + v2
     * - 1
     */
    private static int getEdgeIndex(int v1, int v2, int vertexCount) {
        // Ensure v1 < v2 for upper triangular indexing
        if (v1 > v2) {
            int temp = v1;
            v1 = v2;
            v2 = temp;
        }
        return v1 * (vertexCount - 1) - v1 * (v1 + 1) / 2 + v2 - 1;
    }
}
