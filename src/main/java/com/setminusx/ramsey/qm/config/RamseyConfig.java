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
    private String clientId;

    private Mw mw;
    private Client client;
    private Graph graph;
    private WorkUnit workUnit;
    private Campaign campaign;
    private Stage stage;

    @Data
    public static class Mw {
        private String host;
    }

    @Data
    public static class Client {
        private String url;
        private Registration registration;

        @Data
        public static class Registration {
            private PhoneHome phoneHome;
            private Timeout timeout;

            @Data
            public static class PhoneHome {
                private Long frequencyInMillis;
            }

            @Data
            public static class Timeout {
                private Long frequencyInMillis;
                private Long thresholdInMinutes;
            }
        }
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
    }

}