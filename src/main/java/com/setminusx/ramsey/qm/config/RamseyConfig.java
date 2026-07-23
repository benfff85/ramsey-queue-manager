package com.setminusx.ramsey.qm.config;

import com.setminusx.ramsey.qm.model.WorkUnitAnalysisType;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "ramsey")
public class RamseyConfig {

    private Integer vertexCount;
    private Integer subgraphSize;
    private Integer campaignId;

    private Mw mw;
    private Graph graph;
    private WorkUnit workUnit;
    private Campaign campaign;
    private Stage stage;
    private Perturbation perturbation = new Perturbation();

    /**
     * Iterated-local-search "kick": when a campaign has gone {@code wallStages}
     * stages with no new minimum, restart its descent from a randomly perturbed
     * copy of the campaign's best (minimum) graph. See the fleet-abstraction /
     * perturbation plan docs in ramsey-mw.
     */
    @Data
    public static class Perturbation {
        /** Off by default — enabling changes live search behavior. Env: PERTURBATION_ENABLED */
        private boolean enabled = false;
        /** Wall = this many stages with no new campaign minimum. Env: PERTURBATION_WALL_STAGES */
        private int wallStages = 500;
        /** Base kick strength: flips this many red AND this many blue edges (balance-preserving). Env: PERTURBATION_EDGE_PAIRS */
        private int edgePairs = 60;
        /** Max strength multiplier. Consecutive fruitless kicks escalate GEOMETRICALLY (x1,x2,x4,x8,...)
         *  capped here, so pairs run edgePairs * {1,2,4,...} up to edgePairs*cap. Env: PERTURBATION_ESCALATION_CAP */
        private int escalationCap = 32;
        /** Attempts to find a novel (unvisited) perturbed graph before giving up this tick. */
        private int maxNoveltyRetries = 5;
    }

    @Data
    public static class Mw {
        private String host;
    }

    @Data
    public static class Graph {
        private String url;
    }

    @Data
    public static class WorkUnit {
        private Queue queue;

        @Data
        public static class Queue {
            private String url;
            private List<WorkUnitAnalysisType> analysisType;
            private Long frequencyInMillis;
        }
    }

    @Data
    public static class Campaign {
        private String url;
    }

    @Data
    public static class Stage {
        private String url;
        /**
         * Default work enumeration strategy for new stages.
         * If null, queue-based mode is used. Valid values:
         * BASIC, SINGLE_EDGE_CARDINALITY, DUAL_EDGE_CARDINALITY
         */
        private String defaultWorkEnumerationStrategy;

        /**
         * Number of top results to track per stage for exhaustion fallback.
         * Default: 10
         */
        private Integer topResultsCount = 10;

        /**
         * Delay in milliseconds after stage exhaustion before progressing.
         * Allows in-flight work to complete. Default: 60000 (60 seconds)
         */
        private Long exhaustionDelayMs = 60000L;

        /**
         * Number of recent stages to re-seed into processed_graph_hashes on Redis recovery.
         * Higher values give stronger cycle protection at the cost of more DB fetches on restart.
         * Default: 50
         */
        private Integer cyclePreventionGraphLookbackCount = 50;

        /**
         * When true (default — current behaviour), a stage advances the moment a result beats
         * the base graph's clique count. When false, the stage runs to exhaustion and the best
         * result found across the whole work space is taken at the end.
         * Env: IMMEDIATELY_PROGRESS_STAGE_ON_IMPROVEMENT
         */
        private boolean immediatelyProgressOnImprovement = true;
    }

}