package com.example.monitoramento.monitoring;

import com.example.monitoramento.catalog.*;
import com.example.monitoramento.demo.SimulatedProbeClient;
import com.example.monitoramento.probes.ProbeResult;
import com.example.monitoramento.status.*;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Perfil explícito para testes unitários e demonstração sem banco. Não oferece cadastro durável. */
@Repository
@Profile("memory")
public class MemoryMonitoringStore implements MonitoringStore {
    private final LinkedHashMap<UUID, CircuitMonitor> monitors = new LinkedHashMap<>();
    private final List<CatalogCircuit> catalog;
    private final Instant epoch;

    public MemoryMonitoringStore(Clock clock, MonitoringPolicy policy) {
        epoch = clock.instant();
        var simulator = new SimulatedProbeClient(clock, epoch);
        catalog = java.util.stream.IntStream.range(0, simulator.circuits().size()).mapToObj(index -> {
            var definition = simulator.circuits().get(index);
            monitors.put(definition.id(), new CircuitMonitor(definition, policy));
            return new CatalogCircuit(definition.id(), definition.name(), named("site:" + definition.site()), definition.site(),
                    named("provider:" + definition.provider()), definition.provider(), CircuitRole.valueOf(definition.role()),
                    SimulatedProbeClient.scenario(index), false, epoch, epoch);
        }).toList();
    }
    private static UUID named(String value) { return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    @Override public boolean persisted() { return false; }
    @Override public Instant simulationEpoch() { return epoch; }
    @Override public List<CatalogCircuit> configuredCircuits() { return catalog; }
    @Override public List<CircuitSnapshot> snapshots(Instant now) { return monitors.values().stream().map(m -> m.snapshot(now)).toList(); }
    @Override public Optional<CircuitSnapshot> snapshot(UUID id, Instant now) {
        return Optional.ofNullable(monitors.get(id)).map(m -> m.snapshot(now));
    }
    @Override public CircuitMonitor.Recording record(ProbeResult result, Instant receivedAt) {
        var monitor = monitors.get(result.circuitId());
        if (monitor == null) throw new ResourceMissing("Circuito não encontrado");
        return monitor.record(result, receivedAt);
    }
    @Override public List<Measurement> measurements(UUID id, int limit) {
        throw new ResourceConflict("Histórico de medições exige o perfil postgres");
    }
    @Override public List<IncidentSnapshot> incidents(Instant now, int limit) {
        return snapshots(now).stream().flatMap(c -> c.incidents().stream()).limit(limit).toList();
    }
}
