package com.example.monitoramento.api;

import com.example.monitoramento.demo.DemoMonitoringService;
import com.example.monitoramento.execution.BoundedProbeExecutor;
import com.example.monitoramento.status.Availability;
import com.example.monitoramento.status.CircuitSnapshot;
import com.example.monitoramento.status.IncidentSnapshot;
import com.example.monitoramento.status.Quality;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("demo")
@RequestMapping("/api/v1")
public class MonitoringController {
    private final DemoMonitoringService monitoring;

    public MonitoringController(DemoMonitoringService monitoring) {
        this.monitoring = monitoring;
    }

    @GetMapping("/circuits")
    public List<CircuitSnapshot> circuits() {
        return monitoring.circuits();
    }

    @GetMapping("/circuits/{id}")
    public CircuitSnapshot circuit(@PathVariable UUID id) {
        return monitoring.circuit(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Circuito não encontrado"));
    }

    @GetMapping("/incidents")
    public List<IncidentSnapshot> incidents() {
        return monitoring.incidents();
    }

    @GetMapping("/monitoring/execution")
    public BoundedProbeExecutor.Metrics execution() {
        return monitoring.executionMetrics();
    }

    public record Summary(Instant observedAt, String source, boolean persisted, long totalCircuits,
                          long up, long down, long unknown, long degraded, long openIncidents) {}

    @GetMapping("/dashboard/summary")
    public Summary summary() {
        List<CircuitSnapshot> circuits = monitoring.circuits();
        return new Summary(circuits.getFirst().observedAt(), "SIMULATED", false, circuits.size(),
                circuits.stream().filter(c -> c.availability() == Availability.UP).count(),
                circuits.stream().filter(c -> c.availability() == Availability.DOWN).count(),
                circuits.stream().filter(c -> c.availability() == Availability.UNKNOWN).count(),
                circuits.stream().filter(c -> c.quality() == Quality.DEGRADED).count(),
                circuits.stream().filter(c -> c.activeIncident() != null).count());
    }
}
