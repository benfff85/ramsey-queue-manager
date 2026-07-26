package com.setminusx.ramsey.qm.controller;

import com.setminusx.ramsey.qm.config.RamseyConfig;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StageAdoptSchedulerTest {

    private RamseyConfig config(long settleMs) {
        RamseyConfig config = mock(RamseyConfig.class);
        RamseyConfig.Stage stage = new RamseyConfig.Stage();
        stage.setAdoptSettleMs(settleMs);
        lenient().when(config.getStage()).thenReturn(stage);
        return config;
    }

    @Test
    void settleDisabled_schedulesNothing() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        StageProgressionMonitor monitor = mock(StageProgressionMonitor.class);

        new StageAdoptScheduler(scheduler, monitor, config(0)).onNewBest(42);

        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void firstAnnouncementArmsOnce_repeatsAreIgnored() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        StageProgressionMonitor monitor = mock(StageProgressionMonitor.class);
        StageAdoptScheduler adopt = new StageAdoptScheduler(scheduler, monitor, config(500));

        // Workers publish on EVERY record-break — many per stage; only the first may arm a timer.
        adopt.onNewBest(42);
        adopt.onNewBest(42);
        adopt.onNewBest(42);

        verify(scheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void distinctStagesEachArm() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        StageProgressionMonitor monitor = mock(StageProgressionMonitor.class);
        StageAdoptScheduler adopt = new StageAdoptScheduler(scheduler, monitor, config(500));

        adopt.onNewBest(42);
        adopt.onNewBest(43);

        verify(scheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void firingAdoptsAndReleasesTheArmSoALaterAnnouncementCanReArm() {
        List<Runnable> scheduled = new ArrayList<>();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(inv -> {
                    scheduled.add(inv.getArgument(0));
                    return null;
                });
        StageProgressionMonitor monitor = mock(StageProgressionMonitor.class);
        StageAdoptScheduler adopt = new StageAdoptScheduler(scheduler, monitor, config(500));

        adopt.onNewBest(42);
        assertEquals(1, scheduled.size());
        scheduled.get(0).run(); // timer fires
        verify(monitor).adoptAfterSettle(42);

        // Near the floor a fired timer may not have advanced the stage (candidate already
        // visited); a later announcement must be able to arm again.
        adopt.onNewBest(42);
        assertEquals(2, scheduled.size());
    }

    @Test
    void adoptFailureStillReleasesTheArm() {
        List<Runnable> scheduled = new ArrayList<>();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(inv -> {
                    scheduled.add(inv.getArgument(0));
                    return null;
                });
        StageProgressionMonitor monitor = mock(StageProgressionMonitor.class);
        doThrow(new RuntimeException("middleware down")).when(monitor).adoptAfterSettle(42);
        StageAdoptScheduler adopt = new StageAdoptScheduler(scheduler, monitor, config(500));

        adopt.onNewBest(42);
        assertDoesNotThrow(() -> scheduled.get(0).run());

        adopt.onNewBest(42); // not wedged
        assertEquals(2, scheduled.size());
    }
}
