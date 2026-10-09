package com.example.monitoramento.api;

import com.example.monitoramento.demo.DemoMonitoringService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "monitoring.demo.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class MonitoringControllerTest {
    @Autowired MockMvc mvc;
    @Autowired DemoMonitoringService monitoring;

    @Test
    void listsSyntheticCircuitsWithExplicitUnknownStateAndNoAutomaticExecution() throws Exception {
        mvc.perform(get("/api/v1/circuits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(12)))
                .andExpect(jsonPath("$[0].circuit.site").value("Unidade Centro"))
                .andExpect(jsonPath("$[0].availability").value("UNKNOWN"))
                .andExpect(jsonPath("$[0].counters.tests").value(0));
        assertEquals(0, monitoring.executionMetrics().accepted());
    }

    @Test
    void detailUsesStableCircuitIdentity() throws Exception {
        UUID id = monitoring.circuits().getFirst().circuit().id();
        mvc.perform(get("/api/v1/circuits/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.circuit.id").value(id.toString()));
    }

    @Test
    void unknownAndMalformedCircuitIdsReturnStructuredErrors() throws Exception {
        mvc.perform(get("/api/v1/circuits/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        mvc.perform(get("/api/v1/circuits/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void summaryDisclosesSyntheticSourceAndInMemoryStorage() throws Exception {
        mvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCircuits").value(12))
                .andExpect(jsonPath("$.unknown").value(12))
                .andExpect(jsonPath("$.source").value("SIMULATED"))
                .andExpect(jsonPath("$.persisted").value(false));
        mvc.perform(get("/api/v1/incidents"))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void legacyAliasUsesLiveSnapshotsRatherThanClasspathHistoricalJson() throws Exception {
        mvc.perform(get("/hostdata"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(12)))
                .andExpect(jsonPath("$[0].circuit.site").value("Unidade Centro"));
    }

    @Test
    void exposesExecutionCounters() throws Exception {
        mvc.perform(get("/api/v1/monitoring/execution"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.closed").value(false));
    }
}
