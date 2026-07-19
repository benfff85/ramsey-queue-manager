package com.setminusx.ramsey.qm.utility;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CliqueCounterTest {

    @Test
    void k5AllRedHasExactlyOneMono5Clique() {
        assertEquals(1, CliqueCounter.countMonoCliques("1".repeat(10), 5, 5));
    }

    @Test
    void empty5HasOneBlue5Clique() {
        assertEquals(1, CliqueCounter.countMonoCliques("0".repeat(10), 5, 5));
    }

    @Test
    void k4CannotContain5Cliques() {
        assertEquals(0, CliqueCounter.countMonoCliques("1".repeat(6), 4, 5));
    }

    @Test
    void k8AllRedCounts5CliquesAsBinomial() {
        // C(8,5) = 56 red; complement empty
        assertEquals(56, CliqueCounter.countMonoCliques("1".repeat(28), 8, 5));
    }

    @Test
    void matchesBruteForceOnRandomSmallGraphs() {
        Random r = new Random(42);
        for (int trial = 0; trial < 20; trial++) {
            int n = 8;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n * (n - 1) / 2; i++) {
                sb.append(r.nextBoolean() ? '1' : '0');
            }
            String bits = sb.toString();
            for (int k = 3; k <= 5; k++) {
                assertEquals(bruteForce(bits, n, k), CliqueCounter.countMonoCliques(bits, n, k),
                        "mismatch bits=" + bits + " k=" + k);
            }
        }
    }

    /** Enumerate every k-subset; count monochromatic ones. Reference implementation. */
    private static long bruteForce(String bits, int n, int k) {
        boolean[][] red = new boolean[n][n];
        int idx = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++, idx++) {
                red[i][j] = red[j][i] = bits.charAt(idx) == '1';
            }
        }
        int[] subset = new int[k];
        return countSubsets(red, n, k, 0, 0, subset);
    }

    private static long countSubsets(boolean[][] red, int n, int k, int depth, int start, int[] subset) {
        if (depth == k) {
            boolean allRed = true;
            boolean allBlue = true;
            for (int a = 0; a < k && (allRed || allBlue); a++) {
                for (int b = a + 1; b < k; b++) {
                    if (red[subset[a]][subset[b]]) {
                        allBlue = false;
                    } else {
                        allRed = false;
                    }
                }
            }
            return (allRed ? 1 : 0) + (allBlue ? 1 : 0);
        }
        long c = 0;
        for (int v = start; v < n; v++) {
            subset[depth] = v;
            c += countSubsets(red, n, k, depth + 1, v + 1, subset);
        }
        return c;
    }
}
