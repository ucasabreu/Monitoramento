package com.example.monitoramento.status;

import com.example.monitoramento.circuits.CircuitDefinition;
import com.example.monitoramento.persistence.StateCodec;
import com.example.monitoramento.probes.*;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class MonitorCheckpointTest {
    private final Instant base = Instant.parse("2026-10-09T12:00:00.123456789Z");
    private final CircuitDefinition circuit = new CircuitDefinition(UUID.randomUUID(), "Teste", "Unidade", "Operadora", "PRIMARY");
    private final MonitoringPolicy policy = new MonitoringPolicy(3, 2, Duration.ofSeconds(32), 100, ZoneId.of("America/Sao_Paulo"));
    private final StateCodec codec = new StateCodec(JsonMapper.builder().build());

    private ProbeResult result(int seconds, ProbeOutcome outcome) {
        Instant time = base.plusSeconds(seconds);
        return new ProbeResult(UUID.randomUUID(), circuit.id(), time, time, outcome,
                outcome == ProbeOutcome.SUCCESS ? 0.123 : null, "SIMULATED", "Evidência de teste");
    }
    private CircuitMonitor restore(CircuitMonitor monitor) {
        var saved = monitor.checkpoint();
        var decoded = codec.decode(codec.encode(saved), MonitorCheckpoint.class);
        assertEquals(saved, decoded);
        return new CircuitMonitor(circuit, policy, decoded);
    }

    @Test void continuesPendingFailureConfirmationAfterJsonRoundTrip() {
        var monitor = new CircuitMonitor(circuit, policy);
        for (int second = 0; second < 4; second++) monitor.record(result(second,
                second < 2 ? ProbeOutcome.SUCCESS : ProbeOutcome.FAILURE), base.plusSeconds(second));
        var restarted = restore(monitor);
        assertEquals(monitor.snapshot(base.plusSeconds(3)), restarted.snapshot(base.plusSeconds(3)));
        restarted.record(result(4, ProbeOutcome.FAILURE), base.plusSeconds(4));
        var snapshot = restarted.snapshot(base.plusSeconds(4));
        assertEquals(Availability.DOWN, snapshot.availability());
        assertEquals(base.plusSeconds(2), snapshot.activeIncident().firstFailureAt());
        assertEquals(1, snapshot.counters().confirmedFalls());
    }

    @Test void retainsIncidentIdentityAndPendingRecoveryAfterRestart() {
        var monitor = new CircuitMonitor(circuit, policy);
        for (int second = 0; second < 3; second++) monitor.record(result(second, ProbeOutcome.FAILURE), base.plusSeconds(second));
        UUID id = monitor.snapshot(base.plusSeconds(2)).activeIncident().id();
        monitor.record(result(3, ProbeOutcome.SUCCESS), base.plusSeconds(3));
        var restarted = restore(monitor);
        restarted.record(result(4, ProbeOutcome.SUCCESS), base.plusSeconds(4));
        var snapshot = restarted.snapshot(base.plusSeconds(4));
        assertEquals(Availability.UP, snapshot.availability());
        assertNull(snapshot.activeIncident());
        assertEquals(id, snapshot.incidents().getFirst().id());
        assertEquals(base.plusSeconds(3), snapshot.incidents().getFirst().firstRecoveryAt());
        assertEquals(base.plusSeconds(4), snapshot.incidents().getFirst().recoveryConfirmedAt());
    }

    @Test void marksRestartObservationGapWithoutInventingRecoveryOrAnotherIncident() {
        var monitor = new CircuitMonitor(circuit, policy);
        for (int second = 0; second < 3; second++) monitor.record(result(second, ProbeOutcome.FAILURE), base.plusSeconds(second));
        UUID id = monitor.snapshot(base.plusSeconds(2)).activeIncident().id();
        var restarted = restore(monitor);
        var expired = restarted.snapshot(base.plusSeconds(100));
        assertEquals(Availability.UNKNOWN, expired.availability());
        assertTrue(expired.activeIncident().hasObservationGap());
        assertEquals(id, expired.activeIncident().id());
        assertEquals(3, expired.counters().tests());
        var afterGap = restore(restarted);
        for (int second = 101; second < 104; second++) afterGap.record(result(second, ProbeOutcome.FAILURE), base.plusSeconds(second));
        assertEquals(id, afterGap.snapshot(base.plusSeconds(103)).activeIncident().id());
        assertEquals(0, afterGap.snapshot(base.plusSeconds(103)).counters().confirmedFalls());
    }
}
