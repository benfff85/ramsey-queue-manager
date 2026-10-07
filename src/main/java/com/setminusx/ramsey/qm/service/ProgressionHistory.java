package com.setminusx.ramsey.qm.service;

import com.setminusx.ramsey.qm.client.MiddlewareClient;
import com.setminusx.ramsey.qm.model.ProgressionPoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A constant-size summary of each campaign's progression, kept current from the middleware's paged
 * endpoint.
 *
 * The perturbation check needs only a few aggregates of a campaign's whole history: its size, the
 * incumbent, the kick stages, and the floor of the current basin with how long ago it was reached.
 * It used to refetch the entire series for them on every check, once a minute. By Oct 2026 that
 * series was 3.66M stages and 555 MB, and the fetch took ~11 s. It ran under the campaign lock, so
 * no stage could advance meanwhile and the fleet sat idle for ~18% of wall time. It also ran in a
 * 2 GB container already sitting at 1.72 GiB.
 *
 * Here the history is read once, in pages, and afterwards only the stages after the newest one
 * seen. Retained per campaign: the incumbent and one floor per kick epoch.
 */
public class ProgressionHistory {

    /** Page size for middleware reads; equal to the middleware's own cap, MAX_PROGRESSION_PAGE. */
    static final int PAGE_SIZE = 50_000;

    /** Marker the queue manager writes on a stage created by a perturbation kick. */
    public static final String KICK_DETAILS_PREFIX = "PERTURBATION";

    private final MiddlewareClient middlewareClient;
    private final Map<Integer, Summary> summaries = new ConcurrentHashMap<>();

    public ProgressionHistory(MiddlewareClient middlewareClient) {
        this.middlewareClient = middlewareClient;
    }

    /**
     * Brings the campaign's summary up to the middleware's newest stage and returns it. A failed
     * page read propagates; what was read before it stays, as a valid prefix, and the next call
     * continues from there.
     */
    public Summary refresh(int campaignId) {
        Summary summary = summaries.computeIfAbsent(campaignId, id -> new Summary());
        synchronized (summary) {
            List<ProgressionPoint> page;
            do {
                page = middlewareClient.getProgressionPage(campaignId, summary.lastStageId, PAGE_SIZE);
                page.forEach(summary::accept);
            } while (page.size() == PAGE_SIZE);
        }
        return summary;
    }

    /** The incumbent count from the last refresh, or null if this campaign has not been read yet. */
    public Long knownIncumbentCount(int campaignId) {
        Summary s = summaries.get(campaignId);
        if (s == null) {
            return null;
        }
        Point p = s.incumbent();
        return p == null ? null : p.cliqueCount();
    }

    /** One stage's position in its campaign, as far as the perturbation check needs it. */
    public record Point(int stageId, Integer graphId, long cliqueCount, long idx) {
        static final Comparator<Point> LOWEST_THEN_EARLIEST =
                Comparator.comparingLong(Point::cliqueCount).thenComparingInt(Point::stageId);
    }

    /** The aggregates of one campaign's history. Every access holds its monitor. */
    public static final class Summary {

        /** A run of stages from one kick (or the campaign's start) up to the next kick. */
        private static final class Epoch {
            final int startStageId;
            final boolean kick;
            Point floor;

            Epoch(int startStageId, boolean kick) {
                this.startStageId = startStageId;
                this.kick = kick;
            }
        }

        private int lastStageId;
        private long size;
        private Point incumbent;
        private final List<Epoch> epochs = new ArrayList<>();

        void accept(ProgressionPoint p) {
            if (p.getStageId() <= lastStageId) {
                return;
            }
            lastStageId = p.getStageId();
            long idx = size++;
            boolean kick = p.getDetails() != null && p.getDetails().startsWith(KICK_DETAILS_PREFIX);
            if (kick || epochs.isEmpty()) {
                epochs.add(new Epoch(p.getStageId(), kick));
            }
            if (p.getCliqueCount() == null) {
                return;
            }
            Point point = new Point(p.getStageId(), p.getGraphId(), p.getCliqueCount(), idx);
            // Strictly lower, so the earliest stage reaching a count is the one kept.
            Epoch epoch = epochs.getLast();
            if (epoch.floor == null || point.cliqueCount() < epoch.floor.cliqueCount()) {
                epoch.floor = point;
            }
            if (incumbent == null || point.cliqueCount() < incumbent.cliqueCount()) {
                incumbent = point;
            }
        }

        /** Stages in the campaign's history. */
        public synchronized long size() {
            return size;
        }

        /** The campaign's lowest count, at the earliest stage reaching it; null with no counts. */
        public synchronized Point incumbent() {
            return incumbent;
        }

        /** Stage ids of every kick, ascending. */
        public synchronized List<Integer> kickStageIds() {
            return epochs.stream().filter(e -> e.kick).map(e -> e.startStageId).toList();
        }

        /**
         * The lowest point at or after {@code kickStageId}, earliest on ties: the floor of the
         * basin that kick opened, including every later basin. Null when those stages carry no
         * counts.
         *
         * @throws IllegalArgumentException unless {@code kickStageId} is a kick in this history.
         *         Floors are kept per kick epoch, so any other start would need stages no longer
         *         held.
         */
        public synchronized Point floorSince(int kickStageId) {
            int first = -1;
            for (int i = 0; i < epochs.size(); i++) {
                if (epochs.get(i).kick && epochs.get(i).startStageId == kickStageId) {
                    first = i;
                    break;
                }
            }
            if (first < 0) {
                throw new IllegalArgumentException("stage " + kickStageId + " is not a kick in this history");
            }
            return epochs.subList(first, epochs.size()).stream()
                    .map(e -> e.floor)
                    .filter(java.util.Objects::nonNull)
                    .min(Point.LOWEST_THEN_EARLIEST)
                    .orElse(null);
        }

        /** Stages after {@code point} in the campaign's history. */
        public synchronized long stagesAfter(Point point) {
            return size - 1 - point.idx();
        }
    }
}
