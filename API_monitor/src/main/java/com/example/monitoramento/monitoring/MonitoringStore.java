package com.example.monitoramento.monitoring;

import com.example.monitoramento.catalog.CatalogCircuit;
import com.example.monitoramento.probes.ProbeResult;
import com.example.monitoramento.status.CircuitMonitor;
import com.example.monitoramento.status.CircuitSnapshot;
import com.example.monitoramento.status.IncidentSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MonitoringStore {
    record Measurement(ProbeResult result, Instant receivedAt, CircuitMonitor.Recording processingStatus) {}
    List<CatalogCircuit> configuredCircuits();
    List<CircuitSnapshot> snapshots(Instant now);
    Optional<CircuitSnapshot> snapshot(UUID id, Instant now);
    CircuitMonitor.Recording record(ProbeResult result, Instant receivedAt);
    default CircuitMonitor.Recording record(ProbeResult result, Instant receivedAt, Instant configurationAt) {
        return record(result, receivedAt);
    }
    List<Measurement> measurements(UUID circuitId, int limit);
    List<IncidentSnapshot> incidents(Instant now, int limit);
    Instant simulationEpoch();
    boolean persisted();
}
