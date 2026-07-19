package com.setminusx.ramsey.qm.utility;

/**
 * Monochromatic k-clique counter over the upper-triangular edge bitstring
 * ('1' = red, '0' = blue) — a Java port of the Rust worker's counting kernel
 * (ramsey-worker-rust algorithm.rs: depth parameter, leaf popcount shortcut,
 * k-2 level inline; counts ALL k-cliques, NO pivoting — pivoting would break
 * all-clique counting). Used once per perturbation kick to stamp the perturbed
 * graph's true clique_count, which stage-progression comparisons depend on.
 *
 * Perf: a full 282-vertex count runs in low seconds — fine for once-per-kick,
 * do NOT put this on any hot path.
 */
public final class CliqueCounter {

    private CliqueCounter() {
    }

    /** Total monochromatic k-cliques (red + blue) of the coloring. */
    public static long countMonoCliques(String bits, int vertexCount, int cliqueSize) {
        int expected = vertexCount * (vertexCount - 1) / 2;
        if (bits.length() != expected) {
            throw new IllegalArgumentException(
                    "bitstring length " + bits.length() + " != C(" + vertexCount + ",2) = " + expected);
        }
        int words = (vertexCount + 63) >>> 6;
        long[][] red = new long[vertexCount][words];
        long[][] blue = new long[vertexCount][words];

        // blue starts as "all vertices except self", red empty; each '1' edge
        // moves the pair from blue to red.
        for (int i = 0; i < vertexCount; i++) {
            for (int j = 0; j < vertexCount; j++) {
                if (i != j) {
                    blue[i][j >>> 6] |= 1L << (j & 63);
                }
            }
        }
        int idx = 0;
        for (int i = 0; i < vertexCount; i++) {
            for (int j = i + 1; j < vertexCount; j++, idx++) {
                if (bits.charAt(idx) == '1') {
                    red[i][j >>> 6] |= 1L << (j & 63);
                    red[j][i >>> 6] |= 1L << (i & 63);
                    blue[i][j >>> 6] &= ~(1L << (j & 63));
                    blue[j][i >>> 6] &= ~(1L << (i & 63));
                }
            }
        }
        return countColor(red, vertexCount, cliqueSize, words)
                + countColor(blue, vertexCount, cliqueSize, words);
    }

    private static long countColor(long[][] adjacency, int vertexCount, int cliqueSize, int words) {
        long[] p = new long[words];
        for (int v = 0; v < vertexCount; v++) {
            p[v >>> 6] |= 1L << (v & 63);
        }
        return bk(0, p, adjacency, cliqueSize, words);
    }

    /**
     * Count all k-cliques: depth = committed vertices; duplicates prevented by the
     * shrinking candidate set (no R/X needed — see the Rust kernel's doc comments).
     */
    private static long bk(int depth, long[] p, long[][] adjacency, int cliqueSize, int words) {
        if (depth == cliqueSize) {
            return 1;
        }
        int pCard = cardinality(p);
        if (depth == cliqueSize - 1) {
            return pCard; // leaf shortcut: each candidate completes one clique
        }
        if (depth + pCard < cliqueSize) {
            return 0;
        }
        if (depth == cliqueSize - 2) {
            // k-2 inline: each child takes the leaf shortcut; AND + popcount per candidate.
            long count = 0;
            long[] candidates = p.clone();
            for (int w = 0; w < words; w++) {
                long word = candidates[w];
                while (word != 0) {
                    int v = (w << 6) + Long.numberOfTrailingZeros(word);
                    word &= word - 1;
                    count += intersectCardinality(p, adjacency[v], words);
                    p[v >>> 6] &= ~(1L << (v & 63));
                }
            }
            return count;
        }

        long count = 0;
        long[] candidates = p.clone();
        for (int w = 0; w < words; w++) {
            long word = candidates[w];
            while (word != 0) {
                int v = (w << 6) + Long.numberOfTrailingZeros(word);
                word &= word - 1;
                long[] newP = new long[words];
                for (int i = 0; i < words; i++) {
                    newP[i] = p[i] & adjacency[v][i];
                }
                count += bk(depth + 1, newP, adjacency, cliqueSize, words);
                p[v >>> 6] &= ~(1L << (v & 63));
            }
        }
        return count;
    }

    private static int cardinality(long[] set) {
        int c = 0;
        for (long w : set) {
            c += Long.bitCount(w);
        }
        return c;
    }

    private static long intersectCardinality(long[] a, long[] b, int words) {
        long c = 0;
        for (int i = 0; i < words; i++) {
            c += Long.bitCount(a[i] & b[i]);
        }
        return c;
    }
}
