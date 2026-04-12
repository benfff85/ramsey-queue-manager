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
    }

}