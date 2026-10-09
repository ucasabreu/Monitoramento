package com.example.monitoramento.persistence;

import com.example.monitoramento.catalog.*;
import com.example.monitoramento.catalog.CatalogRequests.*;
import com.example.monitoramento.status.CircuitMonitor;
import com.example.monitoramento.status.MonitoringPolicy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("postgres")
@Transactional
public class JdbcCatalogRepository {
    private static final String CIRCUITS = """
            SELECT c.*, s.name AS site_name, p.name AS provider_name
              FROM circuits c JOIN sites s ON s.id=c.site_id JOIN providers p ON p.id=c.provider_id
            """;
    private final JdbcTemplate jdbc;
    private final StateCodec codec;
    private final MonitoringPolicy policy;
    private final Clock clock;

    public JdbcCatalogRepository(JdbcTemplate jdbc, StateCodec codec, MonitoringPolicy policy, Clock clock) {
        this.jdbc = jdbc; this.codec = codec; this.policy = policy; this.clock = clock;
    }

    public List<Site> sites() { return jdbc.query("SELECT * FROM sites ORDER BY name, id", this::siteRow); }
    public Site site(UUID id) {
        return jdbc.query("SELECT * FROM sites WHERE id=?", this::siteRow, id).stream().findFirst()
                .orElseThrow(() -> new ResourceMissing("Unidade não encontrada"));
    }
    public Site createSite(SiteInput input) {
        UUID id = UUID.randomUUID(); Instant now = clock.instant();
        jdbc.update("INSERT INTO sites VALUES (?, ?, ?, ?, ?)", id, input.name().strip(),
                optional(input.location()), time(now), time(now));
        return site(id);
    }
    public Site updateSite(UUID id, SiteInput input) {
        if (jdbc.update("UPDATE sites SET name=?, location=?, updated_at=? WHERE id=?",
                input.name().strip(), optional(input.location()), time(clock.instant()), id) == 0)
            throw new ResourceMissing("Unidade não encontrada");
        return site(id);
    }
    public void deleteSite(UUID id) {
        if (jdbc.update("DELETE FROM sites WHERE id=?", id) == 0) throw new ResourceMissing("Unidade não encontrada");
    }

    public List<Provider> providers() { return jdbc.query("SELECT * FROM providers ORDER BY name, id", this::providerRow); }
    public Provider provider(UUID id) {
        return jdbc.query("SELECT * FROM providers WHERE id=?", this::providerRow, id).stream().findFirst()
                .orElseThrow(() -> new ResourceMissing("Operadora não encontrada"));
    }
    public Provider createProvider(ProviderInput input) {
        UUID id = UUID.randomUUID(); Instant now = clock.instant();
        jdbc.update("INSERT INTO providers VALUES (?, ?, ?, ?, ?)", id, input.name().strip(),
                optional(input.contact()), time(now), time(now));
        return provider(id);
    }
    public Provider updateProvider(UUID id, ProviderInput input) {
        if (jdbc.update("UPDATE providers SET name=?, contact=?, updated_at=? WHERE id=?",
                input.name().strip(), optional(input.contact()), time(clock.instant()), id) == 0)
            throw new ResourceMissing("Operadora não encontrada");
        return provider(id);
    }
    public void deleteProvider(UUID id) {
        if (jdbc.update("DELETE FROM providers WHERE id=?", id) == 0) throw new ResourceMissing("Operadora não encontrada");
    }

    public List<CatalogCircuit> circuits(boolean includeArchived) {
        return jdbc.query(CIRCUITS + (includeArchived ? "" : " WHERE c.archived_at IS NULL")
                + " ORDER BY c.created_at, c.id", this::circuitRow);
    }
    public Optional<CatalogCircuit> findCircuit(UUID id, boolean lock) {
        return jdbc.query(CIRCUITS + " WHERE c.id=?" + (lock ? " FOR UPDATE OF c" : ""),
                this::circuitRow, id).stream().findFirst();
    }
    public CatalogCircuit circuit(UUID id) {
        return findCircuit(id, false).orElseThrow(() -> new ResourceMissing("Circuito não encontrado"));
    }
    public CatalogCircuit createCircuit(CircuitInput input) {
        site(input.siteId()); provider(input.providerId());
        UUID id = UUID.randomUUID(); Instant now = clock.instant();
        insertCircuit(id, input, now, false);
        CatalogCircuit circuit = circuit(id);
        initializeState(circuit, now);
        return circuit;
    }
    public CatalogCircuit updateCircuit(UUID id, CircuitInput input) {
        CatalogCircuit before = findCircuit(id, true).orElseThrow(() -> new ResourceMissing("Circuito não encontrado"));
        if (before.archived()) throw new ResourceConflict("Circuito arquivado não pode ser alterado");
        site(input.siteId()); provider(input.providerId());
        // updated_at também invalida callbacks de sondas iniciadas com uma configuração anterior.
        jdbc.update("""
                UPDATE circuits SET name=?, site_id=?, provider_id=?, role=?, simulation_scenario=?,
                    updated_at=GREATEST(?, updated_at + interval '1 microsecond') WHERE id=?
                """, input.name().strip(), input.siteId(), input.providerId(), input.role().name(),
                input.simulationScenario().name(), time(clock.instant()), id);
        return circuit(id);
    }

    public UUID seedSite(UUID id, String name) {
        Instant now = clock.instant();
        jdbc.update("INSERT INTO sites VALUES (?, ?, NULL, ?, ?) ON CONFLICT DO NOTHING",
                id, name, time(now), time(now));
        return jdbc.queryForObject("SELECT id FROM sites WHERE id=? OR lower(name)=lower(?) ORDER BY CASE WHEN id=? THEN 0 ELSE 1 END LIMIT 1",
                UUID.class, id, name, id);
    }
    public UUID seedProvider(UUID id, String name) {
        Instant now = clock.instant();
        jdbc.update("INSERT INTO providers VALUES (?, ?, NULL, ?, ?) ON CONFLICT DO NOTHING",
                id, name, time(now), time(now));
        return jdbc.queryForObject("SELECT id FROM providers WHERE id=? OR lower(name)=lower(?) ORDER BY CASE WHEN id=? THEN 0 ELSE 1 END LIMIT 1",
                UUID.class, id, name, id);
    }
    public void seedCircuit(UUID id, String name, UUID siteId, UUID providerId,
                            CircuitRole role, SimulationScenario scenario) {
        Instant now = clock.instant();
        insertCircuit(id, new CircuitInput(name, siteId, providerId, role, scenario), now, true);
        findCircuit(id, false).ifPresent(circuit -> initializeState(circuit, now));
    }
    private void insertCircuit(UUID id, CircuitInput input, Instant now, boolean seed) {
        jdbc.update("""
                INSERT INTO circuits (id, name, site_id, provider_id, role, simulation_scenario, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """ + (seed ? " ON CONFLICT DO NOTHING" : ""), id, input.name().strip(), input.siteId(),
                input.providerId(), input.role().name(), input.simulationScenario().name(), time(now), time(now));
    }
    private void initializeState(CatalogCircuit circuit, Instant now) {
        String checkpoint = codec.encode(new CircuitMonitor(circuit.definition(), policy).checkpoint());
        jdbc.update("INSERT INTO monitor_states VALUES (?, CAST(? AS jsonb), ?) ON CONFLICT DO NOTHING",
                circuit.id(), checkpoint, time(now));
    }

    private Site siteRow(ResultSet rs, int row) throws SQLException {
        return new Site(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("location"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
    private Provider providerRow(ResultSet rs, int row) throws SQLException {
        return new Provider(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("contact"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
    private CatalogCircuit circuitRow(ResultSet rs, int row) throws SQLException {
        return new CatalogCircuit(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getObject("site_id", UUID.class), rs.getString("site_name"),
                rs.getObject("provider_id", UUID.class), rs.getString("provider_name"),
                CircuitRole.valueOf(rs.getString("role")), SimulationScenario.valueOf(rs.getString("simulation_scenario")),
                rs.getTimestamp("archived_at") != null, rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
    static Timestamp time(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static String optional(String value) { return value == null || value.isBlank() ? null : value.strip(); }
}
