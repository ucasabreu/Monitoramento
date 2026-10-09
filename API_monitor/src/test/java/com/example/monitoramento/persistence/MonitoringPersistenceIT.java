package com.example.monitoramento.persistence;

import com.example.monitoramento.MonitoramentoApplication;
import com.example.monitoramento.catalog.*;
import com.example.monitoramento.catalog.CatalogRequests.*;
import com.example.monitoramento.demo.DemoMonitoringService;
import com.example.monitoramento.monitoring.MonitoringStore;
import com.example.monitoramento.probes.*;
import com.example.monitoramento.status.*;
import com.example.monitoramento.support.MutableClock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@Testcontainers
@SpringBootTest(properties = {"monitoring.demo.enabled=false", "monitoring.demo.seed-enabled=false"})
@ActiveProfiles({"demo", "postgres"})
@AutoConfigureMockMvc
@Import(MonitoringPersistenceIT.TimeConfiguration.class)
class MonitoringPersistenceIT {
    private static final Instant BASE = Instant.parse("2026-10-09T12:00:00.123456789Z");
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary MutableClock controlledClock() { return new MutableClock(BASE); }
    }
    @Autowired JdbcCatalogRepository catalog;
    @Autowired JdbcMonitoringStore store;
    @Autowired StateCodec codec;
    @Autowired MonitoringPolicy policy;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired DemoMonitoringService monitoring;
    @Autowired DemoCatalogSeeder seeder;
    private CatalogCircuit circuit;

    @BeforeEach void createCatalog() {
        jdbc.execute("TRUNCATE incidents, measurements, monitor_states, circuits, sites, providers, monitoring_settings");
        clock.set(BASE);
        var site = catalog.createSite(new SiteInput("Unidade de teste", "Local fictício"));
        var provider = catalog.createProvider(new ProviderInput("Operadora de teste", "Contato fictício"));
        circuit = catalog.createCircuit(new CircuitInput("Circuito de teste", site.id(), provider.id(),
                CircuitRole.PRIMARY, SimulationScenario.STABLE));
    }
    private ProbeResult result(int second, ProbeOutcome outcome) {
        Instant time = BASE.plusSeconds(second);
        return new ProbeResult(UUID.randomUUID(), circuit.id(), time, time, outcome,
                outcome == ProbeOutcome.SUCCESS ? 0.123 : null, "SIMULATED", "Evidência de integração");
    }
    private void record(int second, ProbeOutcome outcome) {
        var result = result(second, outcome);
        store.record(result, result.completedAt());
    }
    private JdbcMonitoringStore anotherConsumer() { return new JdbcMonitoringStore(jdbc, catalog, codec, policy, clock, manager); }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }

    @Test void createsValidatedCatalogAndUpdatesNamesWithoutLosingCircuitIdentity() throws Exception {
        mvc.perform(post("/api/v1/catalog/sites").contentType(APPLICATION_JSON).content("{\"name\":\"  Outra unidade  \"}"))
                .andExpect(status().isCreated()).andExpect(header().exists("Location")).andExpect(jsonPath("$.name").value("Outra unidade"));
        mvc.perform(post("/api/v1/catalog/sites").contentType(APPLICATION_JSON).content("{\"name\":\"OUTRA UNIDADE\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        mvc.perform(post("/api/v1/catalog/sites").contentType(APPLICATION_JSON).content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest());
        var invalid = new CircuitInput("Inválido", UUID.randomUUID(), circuit.providerId(), CircuitRole.PRIMARY, SimulationScenario.STABLE);
        mvc.perform(post("/api/v1/catalog/circuits").contentType(APPLICATION_JSON).content(codec.encode(invalid)))
                .andExpect(status().isNotFound());
        var valid = new CircuitInput("Circuito cadastrado por HTTP", circuit.siteId(), circuit.providerId(),
                CircuitRole.STANDALONE, SimulationScenario.COLLECTOR_ERROR);
        mvc.perform(post("/api/v1/catalog/circuits").contentType(APPLICATION_JSON).content(codec.encode(valid)))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.simulationScenario").value("COLLECTOR_ERROR"));
        assertEquals(2, count("monitor_states"));
        record(0, ProbeOutcome.SUCCESS);
        var renamed = new CircuitInput("Nome atualizado", circuit.siteId(), circuit.providerId(), CircuitRole.SECONDARY, SimulationScenario.HIGH_LATENCY);
        mvc.perform(put("/api/v1/catalog/circuits/{id}", circuit.id()).contentType(APPLICATION_JSON).content(codec.encode(renamed)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(circuit.id().toString()));
        assertEquals("Nome atualizado", store.snapshot(circuit.id(), BASE).orElseThrow().circuit().name());
        assertEquals(1, count("measurements"));
        mvc.perform(delete("/api/v1/catalog/sites/{id}", circuit.siteId())).andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/catalog/providers/{id}", circuit.providerId())).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/dashboard/summary")).andExpect(jsonPath("$.persisted").value(true));
    }

    @Test void restoresPendingConfirmationAndIncidentAcrossNewApplicationContexts() {
        record(0, ProbeOutcome.SUCCESS); record(1, ProbeOutcome.SUCCESS);
        record(2, ProbeOutcome.FAILURE); record(3, ProbeOutcome.FAILURE);
        try (var restarted = new SpringApplicationBuilder(MonitoramentoApplication.class)
                .profiles("demo", "postgres").web(WebApplicationType.NONE).run(
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(), "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(), "--monitoring.demo.enabled=false",
                        "--monitoring.demo.seed-enabled=false")) {
            var recovered = restarted.getBean(JdbcMonitoringStore.class);
            var third = result(4, ProbeOutcome.FAILURE);
            recovered.record(third, third.completedAt());
            var down = recovered.snapshot(circuit.id(), BASE.plusSeconds(4)).orElseThrow();
            assertEquals(Availability.DOWN, down.availability());
            assertEquals(BASE.plusSeconds(2), down.activeIncident().firstFailureAt());
            UUID id = down.activeIncident().id();
            record(5, ProbeOutcome.SUCCESS);
            recovered.record(result(6, ProbeOutcome.SUCCESS), BASE.plusSeconds(6));
            var up = store.snapshot(circuit.id(), BASE.plusSeconds(6)).orElseThrow();
            assertEquals(Availability.UP, up.availability());
            assertEquals(id, up.incidents().getFirst().id());
            assertEquals(1, count("incidents"));
            assertEquals(7, up.counters().tests());
            assertEquals(store.simulationEpoch(), recovered.simulationEpoch());
        }
    }

    @Test void durableDeduplicationWorksBeyondTheMemoryWindowAndRejectsConflictingPayloads() {
        var first = result(0, ProbeOutcome.SUCCESS);
        store.record(first, BASE);
        for (int second = 1; second < 270; second++) record(second, ProbeOutcome.SUCCESS);
        assertEquals(CircuitMonitor.Recording.DUPLICATE, anotherConsumer().record(first, BASE.plusSeconds(270)));
        assertEquals(270, count("measurements"));
        assertEquals(270, store.snapshot(circuit.id(), BASE.plusSeconds(270)).orElseThrow().counters().tests());
        var conflicting = new ProbeResult(first.id(), first.circuitId(), first.startedAt(), first.completedAt(),
                ProbeOutcome.SUCCESS, 10.0, first.source(), first.detail());
        assertThrows(ResourceConflict.class, () -> store.record(conflicting, BASE.plusSeconds(270)));
        assertEquals(270, count("measurements"));
    }

    @Test @Timeout(30) void serializesConcurrentConsumersAndDuplicateDeliveriesWithoutLostCounts() throws Exception {
        var other = anotherConsumer();
        var duplicate = result(0, ProbeOutcome.SUCCESS);
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var jobs = new ArrayList<Future<CircuitMonitor.Recording>>();
            for (int index = 0; index < 20; index++) {
                var consumer = index % 2 == 0 ? store : other;
                jobs.add(pool.submit(() -> { gate.await(); return consumer.record(duplicate, BASE); }));
            }
            gate.countDown();
            long accepted = 0;
            for (var job : jobs) if (job.get(10, TimeUnit.SECONDS) == CircuitMonitor.Recording.ACCEPTED) accepted++;
            assertEquals(1, accepted);
            jobs.clear();
            for (int index = 0; index < 40; index++) {
                var consumer = index % 2 == 0 ? store : other;
                var measurement = result(1, ProbeOutcome.SUCCESS);
                jobs.add(pool.submit(() -> consumer.record(measurement, BASE.plusSeconds(1))));
            }
            for (var job : jobs) assertEquals(CircuitMonitor.Recording.ACCEPTED, job.get(10, TimeUnit.SECONDS));
        }
        assertEquals(41, count("measurements"));
        assertEquals(41, store.snapshot(circuit.id(), BASE.plusSeconds(1)).orElseThrow().counters().tests());
    }

    @Test void rollsBackMeasurementStateAndIncidentTogetherAndAllowsRetry() {
        record(0, ProbeOutcome.SUCCESS); record(1, ProbeOutcome.SUCCESS);
        record(2, ProbeOutcome.FAILURE); record(3, ProbeOutcome.FAILURE);
        jdbc.execute("CREATE FUNCTION reject_incident_for_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_incident_for_test BEFORE INSERT ON incidents FOR EACH ROW EXECUTE FUNCTION reject_incident_for_test()");
        var third = result(4, ProbeOutcome.FAILURE);
        try {
            assertThrows(DataAccessException.class, () -> store.record(third, third.completedAt()));
            assertEquals(4, count("measurements"));
            assertEquals(0, count("incidents"));
            assertEquals(2, store.snapshot(circuit.id(), BASE.plusSeconds(3)).orElseThrow().consecutiveFailures());
        } finally {
            jdbc.execute("DROP TRIGGER reject_incident_for_test ON incidents");
            jdbc.execute("DROP FUNCTION reject_incident_for_test()");
        }
        assertEquals(CircuitMonitor.Recording.ACCEPTED, store.record(third, third.completedAt()));
        assertEquals(5, count("measurements"));
        assertEquals(Availability.DOWN, store.snapshot(circuit.id(), BASE.plusSeconds(4)).orElseThrow().availability());
    }

    @Test void expiresRecoveredObservationsAndPreservesGapOnTheSameOpenIncident() {
        record(0, ProbeOutcome.FAILURE); record(1, ProbeOutcome.FAILURE); record(2, ProbeOutcome.FAILURE);
        UUID id = store.snapshot(circuit.id(), BASE.plusSeconds(2)).orElseThrow().activeIncident().id();
        var expired = anotherConsumer().snapshot(circuit.id(), BASE.plusSeconds(100)).orElseThrow();
        assertEquals(Availability.UNKNOWN, expired.availability());
        assertTrue(expired.activeIncident().hasObservationGap());
        assertEquals(id, expired.activeIncident().id());
        record(101, ProbeOutcome.FAILURE); record(102, ProbeOutcome.FAILURE); record(103, ProbeOutcome.FAILURE);
        assertEquals(id, store.snapshot(circuit.id(), BASE.plusSeconds(103)).orElseThrow().activeIncident().id());
        assertTrue(store.incidents(BASE.plusSeconds(103), 100).getFirst().hasObservationGap());
        assertEquals(1, count("incidents"));
    }

    @Test void archivesWithoutDeletingEvidenceAndIgnoresCallbacksFromOldConfigurations() throws Exception {
        var originalVersion = circuit.updatedAt();
        catalog.updateCircuit(circuit.id(), new CircuitInput(circuit.name(), circuit.siteId(), circuit.providerId(),
                circuit.role(), SimulationScenario.ALWAYS_DOWN));
        assertEquals(CircuitMonitor.Recording.OUT_OF_ORDER, store.record(result(0, ProbeOutcome.SUCCESS), BASE, originalVersion));
        assertEquals(0, count("measurements"));
        record(0, ProbeOutcome.FAILURE); record(1, ProbeOutcome.FAILURE); record(2, ProbeOutcome.FAILURE);
        mvc.perform(delete("/api/v1/catalog/circuits/{id}", circuit.id())).andExpect(status().isNoContent());
        assertTrue(catalog.circuit(circuit.id()).archived());
        assertTrue(store.configuredCircuits().isEmpty());
        assertEquals(3, count("measurements"));
        assertEquals(1, count("incidents"));
        assertTrue(store.snapshot(circuit.id(), BASE.plusSeconds(2)).orElseThrow().activeIncident().hasObservationGap());
        store.record(result(3, ProbeOutcome.SUCCESS), BASE.plusSeconds(3));
        assertEquals(3, count("measurements"));
        mvc.perform(get("/api/v1/dashboard/summary")).andExpect(status().isOk()).andExpect(jsonPath("$.totalCircuits").value(0));
    }

    @Test void storesOutOfOrderEvidenceWithoutChangingCountersAndBoundsQueries() throws Exception {
        record(2, ProbeOutcome.SUCCESS);
        var delayed = result(1, ProbeOutcome.FAILURE);
        assertEquals(CircuitMonitor.Recording.OUT_OF_ORDER, store.record(delayed, BASE.plusSeconds(3)));
        assertEquals(2, count("measurements"));
        assertEquals(1, store.snapshot(circuit.id(), BASE.plusSeconds(3)).orElseThrow().counters().tests());
        assertEquals(1, store.measurements(circuit.id(), 1).size());
        mvc.perform(get("/api/v1/circuits/{id}/measurements", circuit.id()).param("limit", "501")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/circuits/{id}/measurements", circuit.id()).param("limit", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[1].processingStatus").value("OUT_OF_ORDER"));
        mvc.perform(get("/api/v1/circuits/{id}/measurements", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test void seedsOnceWithoutOverwritingEditedOrArchivedDemoCircuits() {
        var seed = new DemoCatalogSeeder(catalog, clock, true);
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(tx -> seed.run(null));
        assertEquals(13, count("circuits"));
        var seeded = catalog.circuits(false).stream().filter(c -> !c.id().equals(circuit.id())).findFirst().orElseThrow();
        catalog.updateCircuit(seeded.id(), new CircuitInput("Nome editado pelo usuário", seeded.siteId(), seeded.providerId(),
                seeded.role(), SimulationScenario.HIGH_LATENCY));
        catalog.updateSite(seeded.siteId(), new SiteInput("Unidade renomeada", null));
        catalog.updateProvider(seeded.providerId(), new ProviderInput("Operadora renomeada", null));
        store.archive(seeded.id());
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(tx -> seed.run(null));
        assertEquals(13, count("circuits"));
        assertEquals("Nome editado pelo usuário", catalog.circuit(seeded.id()).name());
        assertEquals("Unidade renomeada", catalog.circuit(seeded.id()).site());
        assertEquals("Operadora renomeada", catalog.circuit(seeded.id()).provider());
        assertTrue(catalog.circuit(seeded.id()).archived());
        assertEquals(13, count("monitor_states"));
    }

    @Test void monitorsNewlyRegisteredCircuitsThroughTheRealBoundedExecutor() {
        monitoring.runCycle();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
            assertEquals(1, store.measurements(circuit.id(), 100).size());
            assertEquals(0, monitoring.executionMetrics().inFlight());
        });
        clock.set(BASE.plusSeconds(10));
        monitoring.runCycle();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
            assertEquals(2, store.measurements(circuit.id(), 100).size());
            assertEquals(0, monitoring.executionMetrics().inFlight());
        });
        var snapshot = store.snapshot(circuit.id(), clock.instant()).orElseThrow();
        assertEquals(Availability.UP, snapshot.availability());
        assertEquals("SIMULATED", snapshot.latestResult().source());
        assertEquals(circuit.id(), snapshot.latestResult().circuitId());
    }
}
