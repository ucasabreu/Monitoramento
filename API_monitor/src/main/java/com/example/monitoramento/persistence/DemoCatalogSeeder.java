package com.example.monitoramento.persistence;

import com.example.monitoramento.catalog.CircuitRole;
import com.example.monitoramento.demo.SimulatedProbeClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import java.util.HashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("demo & postgres")
public class DemoCatalogSeeder implements ApplicationRunner {
    private final JdbcCatalogRepository catalog;
    private final Clock clock;
    private final boolean enabled;
    public DemoCatalogSeeder(JdbcCatalogRepository catalog, Clock clock,
            @Value("${monitoring.demo.seed-enabled:true}") boolean enabled) {
        this.catalog = catalog; this.clock = clock; this.enabled = enabled;
    }
    @Override @Transactional
    public void run(ApplicationArguments arguments) {
        if (!enabled) return;
        var definitions = new SimulatedProbeClient(clock).circuits();
        var sites = new HashMap<String, UUID>();
        var providers = new HashMap<String, UUID>();
        definitions.stream().map(c -> c.site()).distinct().forEach(name -> sites.put(name, catalog.seedSite(named("site:" + name), name)));
        definitions.stream().map(c -> c.provider()).distinct().forEach(name -> providers.put(name, catalog.seedProvider(named("provider:" + name), name)));
        for (int index = 0; index < definitions.size(); index++) {
            var circuit = definitions.get(index);
            catalog.seedCircuit(circuit.id(), circuit.name(), sites.get(circuit.site()), providers.get(circuit.provider()),
                    CircuitRole.valueOf(circuit.role()), SimulatedProbeClient.scenario(index));
        }
    }
    private UUID named(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)); }
}
