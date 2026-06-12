package com.setminusx.ramsey.qm.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
