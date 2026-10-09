package com.example.monitoramento.status;

import com.example.monitoramento.circuits.CircuitDefinition;
import com.example.monitoramento.probes.ProbeResult;
import java.time.Instant;
import java.util.List;

public record CircuitSnapshot(CircuitDefinition circuit, Availability availability, Quality quality,
                              Instant observedAt, Instant lastReliableObservationAt, Long dataAgeSeconds,
                              int consecutiveFailures, int consecutiveSuccesses, ProbeResult latestResult,
                              ProbeCounters counters, IncidentSnapshot activeIncident,
                              List<IncidentSnapshot> incidents, String explanation) {
    public CircuitSnapshot {
        incidents = List.copyOf(incidents);
    }
}
