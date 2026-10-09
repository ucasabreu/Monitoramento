package com.example.monitoramento.catalog;

import com.example.monitoramento.circuits.CircuitDefinition;
import java.time.Instant;
import java.util.UUID;

public record CatalogCircuit(UUID id, String name, UUID siteId, String site, UUID providerId,
        String provider, CircuitRole role, SimulationScenario simulationScenario,
        boolean archived, Instant createdAt, Instant updatedAt) {
    public CircuitDefinition definition() {
        return new CircuitDefinition(id, name, site, provider, role.name());
    }
}
