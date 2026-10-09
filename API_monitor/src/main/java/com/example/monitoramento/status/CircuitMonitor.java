package com.example.monitoramento.status;

import com.example.monitoramento.circuits.CircuitDefinition;
import com.example.monitoramento.probes.ProbeOutcome;
import com.example.monitoramento.probes.ProbeResult;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.UUID;

/** Exclusão por circuito; snapshots não expõem objetos mutáveis às threads HTTP. */
public final class CircuitMonitor {
    public enum Recording { ACCEPTED, DUPLICATE, OUT_OF_ORDER }

    private final CircuitDefinition circuit;
    private final MonitoringPolicy policy;
    private final LinkedHashSet<UUID> recentResults = new LinkedHashSet<>();
    private final LinkedHashMap<YearMonth, long[]> monthlyCounts = new LinkedHashMap<>();
    private final ArrayDeque<IncidentSnapshot> history = new ArrayDeque<>();
    private Availability availability = Availability.UNKNOWN;
    private Quality quality = Quality.UNKNOWN;
    private ProbeResult latest;
    private Instant lastReliableAt;
    private Instant firstFailureAt;
    private Instant firstRecoveryAt;
    private boolean candidateWasUp;
    private int consecutiveFailures;
    private int consecutiveSuccesses;
    private long successes;
    private long failures;
    private long errors;
    private long falls;
    private OpenIncident active;

    public CircuitMonitor(CircuitDefinition circuit, MonitoringPolicy policy) {
        this.circuit = Objects.requireNonNull(circuit);
        this.policy = Objects.requireNonNull(policy);
    }

    public synchronized Recording record(ProbeResult result, Instant receivedAt) {
        if (!circuit.id().equals(result.circuitId())) {
            throw new IllegalArgumentException("Resultado pertence a outro circuito");
        }
        if (result.completedAt().isAfter(receivedAt)) {
            throw new IllegalArgumentException("Medição tem horário futuro");
        }
        if (recentResults.contains(result.id())) return Recording.DUPLICATE;
        if (latest != null && (result.startedAt().isBefore(latest.startedAt())
                || result.completedAt().isBefore(latest.completedAt()))) return Recording.OUT_OF_ORDER;

        expire(result.completedAt());
        recentResults.add(result.id());
        if (recentResults.size() > 256) recentResults.remove(recentResults.iterator().next());
        latest = result;
        YearMonth month = YearMonth.from(result.completedAt().atZone(policy.reportingZone()));
        long[] monthly = monthlyCounts.computeIfAbsent(month, ignored -> new long[3]);
        if (monthlyCounts.size() > 24) monthlyCounts.remove(monthlyCounts.keySet().iterator().next());

        if (result.outcome() == ProbeOutcome.ERROR) {
            errors++;
            monthly[2]++;
            becomeUnknown();
            return Recording.ACCEPTED;
        }
        lastReliableAt = result.completedAt();
        monthly[0]++;
        if (result.outcome() == ProbeOutcome.FAILURE) {
            failures++;
            monthly[1]++;
            acceptFailure(result.completedAt());
        } else {
            successes++;
            acceptSuccess(result);
        }
        return Recording.ACCEPTED;
    }

    private void acceptFailure(Instant at) {
        consecutiveSuccesses = 0;
        firstRecoveryAt = null;
        if (consecutiveFailures == 0) {
            firstFailureAt = at;
            candidateWasUp = availability == Availability.UP;
        }
        if (consecutiveFailures < policy.failureThreshold()) consecutiveFailures++;
        if (consecutiveFailures >= policy.failureThreshold()) {
            if (active == null) {
                active = new OpenIncident(UUID.randomUUID(), firstFailureAt, at, !candidateWasUp);
                if (candidateWasUp) falls++;
            }
            availability = Availability.DOWN;
            quality = Quality.UNKNOWN;
        } else if (availability == Availability.UP) {
            quality = Quality.DEGRADED;
        }
    }

    private void acceptSuccess(ProbeResult result) {
        consecutiveFailures = 0;
        firstFailureAt = null;
        if (consecutiveSuccesses == 0) firstRecoveryAt = result.completedAt();
        if (consecutiveSuccesses < policy.recoveryThreshold()) consecutiveSuccesses++;
        if (availability == Availability.UP || consecutiveSuccesses >= policy.recoveryThreshold()) {
            availability = Availability.UP;
            quality = result.latencyMs() > policy.degradedLatencyMs() ? Quality.DEGRADED : Quality.GOOD;
            if (active != null) {
                history.addLast(active.snapshot(circuit.id(), firstRecoveryAt, result.completedAt(), result.completedAt()));
                if (history.size() > 100) history.removeFirst();
                active = null;
            }
        }
    }

    private void expire(Instant now) {
        if (lastReliableAt != null && !now.isBefore(lastReliableAt.plus(policy.observationTtl()))) {
            becomeUnknown();
        }
    }

    private void becomeUnknown() {
        availability = Availability.UNKNOWN;
        quality = Quality.UNKNOWN;
        consecutiveFailures = 0;
        consecutiveSuccesses = 0;
        firstFailureAt = null;
        firstRecoveryAt = null;
        if (active != null) active.hasGap = true;
    }

    public synchronized CircuitSnapshot snapshot(Instant now) {
        expire(now);
        YearMonth month = YearMonth.from(now.atZone(policy.reportingZone()));
        long[] monthly = monthlyCounts.getOrDefault(month, new long[3]);
        IncidentSnapshot current = active == null ? null
                : active.snapshot(circuit.id(), firstRecoveryAt, null, now);
        var incidents = new ArrayList<>(history);
        if (current != null) incidents.add(current);
        return new CircuitSnapshot(circuit, availability, quality, now, lastReliableAt,
                lastReliableAt == null ? null : Math.max(0, Duration.between(lastReliableAt, now).toSeconds()),
                consecutiveFailures, consecutiveSuccesses, latest,
                new ProbeCounters(successes + failures, successes, failures, errors, falls, month.toString(),
                        monthly[0], monthly[1], monthly[2]), current, incidents, explanation(now));
    }

    private String explanation(Instant now) {
        if (latest == null) return "Aguardando medições sintéticas.";
        if (availability == Availability.UNKNOWN) {
            if (latest.outcome() == ProbeOutcome.ERROR) return "Sem observação confiável: " + latest.detail();
            if (lastReliableAt != null && !now.isBefore(lastReliableAt.plus(policy.observationTtl()))) {
                return "Observações expiradas; o estado do circuito não pode ser confirmado.";
            }
            return "Aguardando observações suficientes para confirmar o estado.";
        }
        if (availability == Availability.DOWN) return "Indisponibilidade confirmada pelos testes; consultar as evidências do incidente.";
        if (quality == Quality.DEGRADED) return "Circuito acessível com evidência de degradação ou falha pendente de confirmação.";
        return "Circuito acessível nas últimas observações confirmadas.";
    }

    private static final class OpenIncident {
        private final UUID id;
        private final Instant beganAt;
        private final Instant confirmedAt;
        private final boolean initialDown;
        private boolean hasGap;

        private OpenIncident(UUID id, Instant beganAt, Instant confirmedAt, boolean initialDown) {
            this.id = id;
            this.beganAt = beganAt;
            this.confirmedAt = confirmedAt;
            this.initialDown = initialDown;
        }

        private IncidentSnapshot snapshot(UUID circuitId, Instant recoveryAt, Instant recoveryConfirmedAt, Instant now) {
            Instant end = recoveryConfirmedAt == null ? now : recoveryAt;
            return new IncidentSnapshot(id, circuitId, beganAt, confirmedAt, recoveryAt, recoveryConfirmedAt,
                    initialDown, hasGap, Math.max(0, Duration.between(beganAt, end).toSeconds()));
        }
    }
}
