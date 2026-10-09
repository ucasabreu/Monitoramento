package com.example.monitoramento.persistence;

import com.example.monitoramento.catalog.*;
import com.example.monitoramento.monitoring.MonitoringStore;
import com.example.monitoramento.probes.ProbeResult;
import com.example.monitoramento.status.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.example.monitoramento.persistence.JdbcCatalogRepository.time;

/** O lock de linha e a transação protegem também consumidores em processos diferentes. */
@Repository
@Profile("postgres")
@DependsOnDatabaseInitialization
public class JdbcMonitoringStore implements MonitoringStore {
    private final JdbcTemplate jdbc;
    private final JdbcCatalogRepository catalog;
    private final StateCodec codec;
    private final MonitoringPolicy policy;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public JdbcMonitoringStore(JdbcTemplate jdbc, JdbcCatalogRepository catalog, StateCodec codec,
            MonitoringPolicy policy, Clock clock, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.catalog = catalog; this.codec = codec; this.policy = policy; this.clock = clock;
        transactions = new TransactionTemplate(manager);
    }

    @Override public boolean persisted() { return true; }
    @Override public List<CatalogCircuit> configuredCircuits() { return catalog.circuits(false); }
    @Override public Instant simulationEpoch() {
        return transactions.execute(status -> {
            jdbc.update("INSERT INTO monitoring_settings VALUES ('simulation_epoch', ?) ON CONFLICT DO NOTHING",
                    clock.instant().toString());
            return Instant.parse(jdbc.queryForObject("SELECT value FROM monitoring_settings WHERE key='simulation_epoch'", String.class));
        });
    }

    @Override public List<CircuitSnapshot> snapshots(Instant now) {
        return configuredCircuits().stream().map(c -> snapshot(c.id(), now)).flatMap(Optional::stream).toList();
    }
    @Override public Optional<CircuitSnapshot> snapshot(UUID id, Instant now) {
        return transactions.execute(status -> catalog.findCircuit(id, true).map(circuit -> {
            var previous = checkpoint(id);
            var monitor = new CircuitMonitor(circuit.definition(), policy, previous);
            if (circuit.archived()) monitor.suspend();
            var snapshot = monitor.snapshot(now);
            save(previous, monitor, snapshot);
            if (circuit.archived()) return new CircuitSnapshot(snapshot.circuit(), snapshot.availability(), snapshot.quality(),
                    snapshot.observedAt(), snapshot.lastReliableObservationAt(), snapshot.dataAgeSeconds(),
                    snapshot.consecutiveFailures(), snapshot.consecutiveSuccesses(), snapshot.latestResult(),
                    snapshot.counters(), snapshot.activeIncident(), snapshot.incidents(),
                    "Circuito arquivado; monitoramento suspenso e histórico preservado.");
            return snapshot;
        }));
    }

    @Override public CircuitMonitor.Recording record(ProbeResult result, Instant receivedAt) {
        return record(result, receivedAt, null);
    }
    @Override public CircuitMonitor.Recording record(ProbeResult result, Instant receivedAt, Instant configurationAt) {
        if (result.completedAt().isAfter(receivedAt)) throw new IllegalArgumentException("Medição tem horário futuro");
        return transactions.execute(status -> {
            CatalogCircuit circuit = catalog.findCircuit(result.circuitId(), true)
                    .orElseThrow(() -> new ResourceMissing("Circuito não encontrado"));
            var existing = existing(result.id());
            if (existing.isPresent()) return duplicate(result, existing.get());
            if (circuit.archived() || (configurationAt != null && !configurationAt.equals(circuit.updatedAt()))) {
                // Callback atrasado de configuração anterior: não altera estado, histórico ou contadores.
                return CircuitMonitor.Recording.OUT_OF_ORDER;
            }
            var previous = checkpoint(circuit.id());
            var monitor = new CircuitMonitor(circuit.definition(), policy, previous);
            var recording = monitor.record(result, receivedAt);
            int inserted = jdbc.update("""
                    INSERT INTO measurements (id, circuit_id, started_at, completed_at, received_at, outcome,
                        latency_ms, source, detail, processing_status, result)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb)) ON CONFLICT (id) DO NOTHING
                    """, result.id(), result.circuitId(), time(result.startedAt()), time(result.completedAt()),
                    time(receivedAt), result.outcome().name(), result.latencyMs(), result.source(), result.detail(),
                    recording.name(), codec.encode(result));
            if (inserted == 0) return duplicate(result, existing(result.id()).orElseThrow());
            save(previous, monitor, monitor.snapshot(receivedAt));
            return recording;
        });
    }

    private Optional<ProbeResult> existing(UUID id) {
        return jdbc.query("SELECT result::text FROM measurements WHERE id=?",
                (rs, row) -> codec.decode(rs.getString(1), ProbeResult.class), id).stream().findFirst();
    }
    private CircuitMonitor.Recording duplicate(ProbeResult incoming, ProbeResult existing) {
        if (!incoming.equals(existing)) throw new ResourceConflict("ID de medição já utilizado com outro conteúdo");
        return CircuitMonitor.Recording.DUPLICATE;
    }
    private MonitorCheckpoint checkpoint(UUID id) {
        return codec.decode(jdbc.queryForObject("SELECT checkpoint::text FROM monitor_states WHERE circuit_id=?",
                String.class, id), MonitorCheckpoint.class);
    }
    private void save(MonitorCheckpoint previous, CircuitMonitor monitor, CircuitSnapshot snapshot) {
        var current = monitor.checkpoint();
        if (!current.equals(previous)) jdbc.update("UPDATE monitor_states SET checkpoint=CAST(? AS jsonb), updated_at=? WHERE circuit_id=?",
                codec.encode(current), time(snapshot.observedAt()), snapshot.circuit().id());
        var oldIncidents = new HashMap<UUID, IncidentSnapshot>();
        previous.history().forEach(incident -> oldIncidents.put(incident.id(), incident));
        if (previous.activeIncident() != null) oldIncidents.put(previous.activeIncident().id(), previous.activeIncident());
        for (var incident : snapshot.incidents()) {
            if (incident.equals(oldIncidents.get(incident.id()))) continue;
            jdbc.update("""
                    INSERT INTO incidents (id, circuit_id, first_failure_at, down_confirmed_at, first_recovery_at,
                        recovery_confirmed_at, began_without_confirmed_up, has_observation_gap, evidence)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb)) ON CONFLICT (id) DO UPDATE SET
                        first_recovery_at=EXCLUDED.first_recovery_at, recovery_confirmed_at=EXCLUDED.recovery_confirmed_at,
                        has_observation_gap=EXCLUDED.has_observation_gap, evidence=EXCLUDED.evidence
                    """, incident.id(), incident.circuitId(), time(incident.firstFailureAt()), time(incident.downConfirmedAt()),
                    time(incident.firstRecoveryAt()), time(incident.recoveryConfirmedAt()), incident.beganWithoutConfirmedUp(),
                    incident.hasObservationGap(), codec.encode(incident));
        }
    }

    public void archive(UUID id) {
        transactions.executeWithoutResult(status -> {
            CatalogCircuit circuit = catalog.findCircuit(id, true).orElseThrow(() -> new ResourceMissing("Circuito não encontrado"));
            if (circuit.archived()) return;
            var previous = checkpoint(id);
            var monitor = new CircuitMonitor(circuit.definition(), policy, previous);
            monitor.suspend();
            Instant now = clock.instant();
            jdbc.update("UPDATE circuits SET archived_at=?, updated_at=GREATEST(?, updated_at + interval '1 microsecond') WHERE id=?",
                    time(now), time(now), id);
            save(previous, monitor, monitor.snapshot(now));
        });
    }

    @Override public List<Measurement> measurements(UUID circuitId, int limit) {
        catalog.circuit(circuitId);
        return jdbc.query("""
                SELECT result::text, received_at, processing_status FROM measurements
                 WHERE circuit_id=? ORDER BY completed_at DESC, id LIMIT ?
                """, (rs, row) -> new Measurement(codec.decode(rs.getString(1), ProbeResult.class),
                        rs.getTimestamp(2).toInstant(), CircuitMonitor.Recording.valueOf(rs.getString(3))), circuitId, limit);
    }

    @Override public List<IncidentSnapshot> incidents(Instant now, int limit) {
        snapshots(now); // Aplica expiração às evidências dos incidentes abertos.
        return jdbc.query("SELECT evidence::text FROM incidents ORDER BY first_failure_at DESC, id LIMIT ?",
                (rs, row) -> elapsed(codec.decode(rs.getString(1), IncidentSnapshot.class), now), limit);
    }
    private IncidentSnapshot elapsed(IncidentSnapshot incident, Instant now) {
        Instant end = incident.recoveryConfirmedAt() == null ? now : incident.firstRecoveryAt();
        return new IncidentSnapshot(incident.id(), incident.circuitId(), incident.firstFailureAt(), incident.downConfirmedAt(),
                incident.firstRecoveryAt(), incident.recoveryConfirmedAt(), incident.beganWithoutConfirmedUp(),
                incident.hasObservationGap(), Math.max(0, Duration.between(incident.firstFailureAt(), end).toSeconds()));
    }
}
