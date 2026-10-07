package com.setminusx.ramsey.qm.client;

import com.setminusx.ramsey.qm.config.RamseyConfig;
import com.setminusx.ramsey.qm.model.Graph;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MiddlewareClientStoredGraphTest {

    @Test
    void storedGraphAsksForNoRebuildAndMapsLineage() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        RamseyConfig cfg = mock(RamseyConfig.class, RETURNS_DEEP_STUBS);
        when(cfg.getGraph().getUrl()).thenReturn("http://mw/api/ramsey/graphs");
        MiddlewareClient client = new MiddlewareClient(cfg, rest);
        server.expect(requestTo("http://mw/api/ramsey/graphs/9?reconstruct=none"))
                .andRespond(withSuccess("""
                        {"graphId":9,"parentGraphId":8,"flippedEdges":"{{0:1}}","graphHash":"ab","lineageDepth":3,"edgeData":null}
                        """, APPLICATION_JSON));

        Graph g = client.getStoredGraph(9);

        assertEquals(8, g.getParentGraphId());
        assertEquals("{{0:1}}", g.getFlippedEdges());
        assertEquals("ab", g.getGraphHash());
        assertEquals(3, g.getLineageDepth());
        assertNull(g.getEdgeData());
        server.verify();
    }

    @Test
    void graphStorageDefaultsToOffWithACheckpointEvery1000() {
        RamseyConfig.GraphStorage gs = new RamseyConfig.GraphStorage();
        assertEquals(RamseyConfig.GraphStorage.Mode.OFF, gs.getMode());
        assertEquals(1000, gs.getCheckpointInterval());
    }
}
