package com.example.monitoramento.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class CatalogRequests {
    private CatalogRequests() {}
    public record SiteInput(@NotBlank @Size(max = 120) String name, @Size(max = 240) String location) {}
    public record ProviderInput(@NotBlank @Size(max = 120) String name, @Size(max = 240) String contact) {}
    public record CircuitInput(@NotBlank @Size(max = 160) String name, @NotNull UUID siteId,
            @NotNull UUID providerId, @NotNull CircuitRole role, @NotNull SimulationScenario simulationScenario) {}
}
