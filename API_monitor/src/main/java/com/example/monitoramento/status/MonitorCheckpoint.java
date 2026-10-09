package com.example.monitoramento.status;

import com.example.monitoramento.probes.ProbeResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Estado suficiente para continuar as regras sem reler todas as medições. */
public record MonitorCheckpoint(int version, Availability availability, Quality quality,
        ProbeResult latest, Instant lastReliableAt, Instant firstFailureAt, Instant firstRecoveryAt,
        boolean candidateWasUp, int consecutiveFailures, int consecutiveSuccesses,
        long successes, long failures, long errors, long falls, List<UUID> recentResults,
        List<MonthCounts> monthlyCounts, List<IncidentSnapshot> history, IncidentSnapshot activeIncident) {
    public MonitorCheckpoint {
        if (version != 1) throw new IllegalArgumentException("Versão de estado não suportada: " + version);
        recentResults = List.copyOf(recentResults);
        monthlyCounts = List.copyOf(monthlyCounts);
        history = List.copyOf(history);
    }

    public record MonthCounts(String month, long tests, long failures, long errors) {}
}
