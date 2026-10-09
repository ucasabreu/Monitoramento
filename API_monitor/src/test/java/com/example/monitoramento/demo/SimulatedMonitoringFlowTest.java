package com.example.monitoramento.demo;

import com.example.monitoramento.status.Availability;
import com.example.monitoramento.status.CircuitMonitor;
import com.example.monitoramento.status.MonitoringPolicy;
import com.example.monitoramento.status.Quality;
import com.example.monitoramento.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SimulatedMonitoringFlowTest {
    @Test
    void reproducesFailuresRecoveryUnknownAndDegradationWithoutNetworkOrSleeping() {
        Instant beganAt = Instant.parse("2026-10-08T12:00:00Z");
        var clock = new MutableClock(beganAt);
        var simulation = new SimulatedProbeClient(clock);
        var policy = new MonitoringPolicy(3, 2, Duration.ofSeconds(32), 100, ZoneId.of("America/Sao_Paulo"));
        var monitors = new LinkedHashMap<UUID, CircuitMonitor>();
        simulation.circuits().forEach(circuit -> monitors.put(circuit.id(), new CircuitMonitor(circuit, policy)));
        assertEquals(12, monitors.size());
        assertEquals(6, simulation.circuits().stream().map(c -> c.site()).distinct().count());
        assertEquals(3, simulation.circuits().stream().map(c -> c.provider()).distinct().count());

        UUID center = simulation.circuits().get(0).id();
        UUID northPrimary = simulation.circuits().get(2).id();
        UUID northSecondary = simulation.circuits().get(3).id();
        UUID south = simulation.circuits().get(4).id();
        UUID east = simulation.circuits().get(6).id();
        UUID west = simulation.circuits().get(8).id();
        UUID annex = simulation.circuits().get(10).id();
        for (int seconds = 0; seconds <= 90; seconds += 10) {
            clock.set(beganAt.plusSeconds(seconds));
            simulation.circuits().forEach(circuit -> {
                var result = simulation.probe(circuit);
                assertEquals("SIMULATED", result.source());
                monitors.get(circuit.id()).record(result, clock.instant());
            });
            if (seconds == 20) {
                assertEquals(Availability.UP, monitors.get(northPrimary).snapshot(clock.instant()).availability());
                assertEquals(Availability.DOWN, monitors.get(northSecondary).snapshot(clock.instant()).availability());
                assertTrue(monitors.get(annex).snapshot(clock.instant()).activeIncident().beganWithoutConfirmedUp());
            }
            if (seconds == 40) {
                assertEquals(Availability.DOWN, monitors.get(center).snapshot(clock.instant()).availability());
                assertEquals(Availability.UNKNOWN, monitors.get(south).snapshot(clock.instant()).availability());
                assertEquals(Quality.DEGRADED, monitors.get(east).snapshot(clock.instant()).quality());
                assertTrue(monitors.get(west).snapshot(clock.instant()).incidents().isEmpty());
            }
        }
        assertEquals(Availability.UP, monitors.get(center).snapshot(clock.instant()).availability());
        assertEquals(1, monitors.get(center).snapshot(clock.instant()).incidents().size());
        assertEquals(Availability.UP, monitors.get(south).snapshot(clock.instant()).availability());
    }
}
