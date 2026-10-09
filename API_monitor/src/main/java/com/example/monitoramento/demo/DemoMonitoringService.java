package com.example.monitoramento.demo;

import com.example.monitoramento.execution.BoundedProbeExecutor;
import com.example.monitoramento.status.CircuitMonitor;
import com.example.monitoramento.status.CircuitSnapshot;
import com.example.monitoramento.status.IncidentSnapshot;
import com.example.monitoramento.status.MonitoringPolicy;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("demo")
public class DemoMonitoringService {
    private final Clock clock;
    private final BoundedProbeExecutor executor;
    private final MonitoringProperties properties;
    private final SimulatedProbeClient simulator;
    private final Map<UUID, CircuitMonitor> monitors;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "monitor-cycle");
        thread.setDaemon(true);
        return thread;
    });

    public DemoMonitoringService(Clock clock, BoundedProbeExecutor executor,
                                 MonitoringPolicy policy, MonitoringProperties properties) {
        this.clock = clock;
        this.executor = executor;
        this.properties = properties;
        simulator = new SimulatedProbeClient(clock);
        Map<UUID, CircuitMonitor> configured = new LinkedHashMap<>();
        simulator.circuits().forEach(circuit -> configured.put(circuit.id(), new CircuitMonitor(circuit, policy)));
        monitors = Collections.unmodifiableMap(configured);
    }

    @PostConstruct
    void start() {
        if (properties.demo().enabled()) {
            scheduler.scheduleWithFixedDelay(this::runCycle, 0, properties.demo().interval().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    public void runCycle() {
        simulator.circuits().forEach(circuit -> {
            CircuitMonitor monitor = monitors.get(circuit.id());
            monitor.snapshot(clock.instant());
            executor.submit(circuit, simulator, result -> monitor.record(result, clock.instant()));
        });
    }

    public List<CircuitSnapshot> circuits() {
        Instant now = clock.instant();
        return monitors.values().stream().map(monitor -> monitor.snapshot(now)).toList();
    }

    public Optional<CircuitSnapshot> circuit(UUID id) {
        return Optional.ofNullable(monitors.get(id)).map(monitor -> monitor.snapshot(clock.instant()));
    }

    public List<IncidentSnapshot> incidents() {
        return circuits().stream().flatMap(circuit -> circuit.incidents().stream()).toList();
    }

    public BoundedProbeExecutor.Metrics executionMetrics() {
        return executor.metrics();
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }
}
