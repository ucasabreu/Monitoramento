package com.example.monitoramento.demo;

import com.example.monitoramento.execution.BoundedProbeExecutor;
import com.example.monitoramento.monitoring.MonitoringStore;
import com.example.monitoramento.status.CircuitSnapshot;
import com.example.monitoramento.status.IncidentSnapshot;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@Profile("demo")
public class DemoMonitoringService {
    private static final Logger log = LoggerFactory.getLogger(DemoMonitoringService.class);
    private final Clock clock;
    private final BoundedProbeExecutor executor;
    private final MonitoringProperties properties;
    private final MonitoringStore store;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "monitor-cycle"); thread.setDaemon(true); return thread;
    });
    private volatile SimulatedProbeClient simulator;

    public DemoMonitoringService(Clock clock, BoundedProbeExecutor executor,
            MonitoringProperties properties, MonitoringStore store) {
        this.clock = clock; this.executor = executor; this.properties = properties; this.store = store;
    }
    @EventListener(ApplicationReadyEvent.class)
    void start() {
        simulator = new SimulatedProbeClient(clock, store.simulationEpoch());
        if (properties.demo().enabled()) scheduler.scheduleWithFixedDelay(this::safeCycle, 0,
                properties.demo().interval().toMillis(), TimeUnit.MILLISECONDS);
    }
    private void safeCycle() {
        try { runCycle(); }
        catch (RuntimeException error) { log.error("Falha no ciclo; nova tentativa na próxima cadência", error); }
    }
    public void runCycle() {
        if (simulator == null) simulator = new SimulatedProbeClient(clock, store.simulationEpoch());
        for (var circuit : store.configuredCircuits()) {
            store.snapshot(circuit.id(), clock.instant());
            executor.submit(circuit.definition(), definition -> simulator.probe(definition, circuit.simulationScenario()),
                    result -> store.record(result, clock.instant(), circuit.updatedAt()));
        }
    }
    public List<CircuitSnapshot> circuits() { return store.snapshots(clock.instant()); }
    public Optional<CircuitSnapshot> circuit(UUID id) { return store.snapshot(id, clock.instant()); }
    public List<IncidentSnapshot> incidents(int limit) { return store.incidents(clock.instant(), limit); }
    public List<MonitoringStore.Measurement> measurements(UUID id, int limit) { return store.measurements(id, limit); }
    public BoundedProbeExecutor.Metrics executionMetrics() { return executor.metrics(); }
    public boolean persisted() { return store.persisted(); }
    public java.time.Instant observedAt() { return clock.instant(); }
    @PreDestroy void stop() {
        scheduler.shutdownNow();
        // Encerra callbacks enquanto o store e o pool JDBC ainda estão disponíveis.
        executor.close();
    }
}
