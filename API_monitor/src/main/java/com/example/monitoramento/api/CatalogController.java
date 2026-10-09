package com.example.monitoramento.api;

import com.example.monitoramento.catalog.*;
import com.example.monitoramento.catalog.CatalogRequests.*;
import com.example.monitoramento.persistence.JdbcCatalogRepository;
import com.example.monitoramento.persistence.JdbcMonitoringStore;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("postgres")
@RequestMapping("/api/v1/catalog")
public class CatalogController {
    private final JdbcCatalogRepository catalog;
    private final JdbcMonitoringStore monitoring;
    public CatalogController(JdbcCatalogRepository catalog, JdbcMonitoringStore monitoring) {
        this.catalog = catalog; this.monitoring = monitoring;
    }
    @GetMapping("/sites") public List<Site> sites() { return catalog.sites(); }
    @GetMapping("/sites/{id}") public Site site(@PathVariable UUID id) { return catalog.site(id); }
    @PostMapping("/sites") public ResponseEntity<Site> createSite(@Valid @RequestBody SiteInput input) {
        Site created = catalog.createSite(input);
        return ResponseEntity.created(URI.create("/api/v1/catalog/sites/" + created.id())).body(created);
    }
    @PutMapping("/sites/{id}") public Site updateSite(@PathVariable UUID id, @Valid @RequestBody SiteInput input) {
        return catalog.updateSite(id, input);
    }
    @DeleteMapping("/sites/{id}") public ResponseEntity<Void> deleteSite(@PathVariable UUID id) {
        catalog.deleteSite(id); return ResponseEntity.noContent().build();
    }
    @GetMapping("/providers") public List<Provider> providers() { return catalog.providers(); }
    @GetMapping("/providers/{id}") public Provider provider(@PathVariable UUID id) { return catalog.provider(id); }
    @PostMapping("/providers") public ResponseEntity<Provider> createProvider(@Valid @RequestBody ProviderInput input) {
        Provider created = catalog.createProvider(input);
        return ResponseEntity.created(URI.create("/api/v1/catalog/providers/" + created.id())).body(created);
    }
    @PutMapping("/providers/{id}") public Provider updateProvider(@PathVariable UUID id, @Valid @RequestBody ProviderInput input) {
        return catalog.updateProvider(id, input);
    }
    @DeleteMapping("/providers/{id}") public ResponseEntity<Void> deleteProvider(@PathVariable UUID id) {
        catalog.deleteProvider(id); return ResponseEntity.noContent().build();
    }
    @GetMapping("/circuits") public List<CatalogCircuit> circuits(@RequestParam(defaultValue = "false") boolean includeArchived) {
        return catalog.circuits(includeArchived);
    }
    @GetMapping("/circuits/{id}") public CatalogCircuit circuit(@PathVariable UUID id) { return catalog.circuit(id); }
    @PostMapping("/circuits") public ResponseEntity<CatalogCircuit> createCircuit(@Valid @RequestBody CircuitInput input) {
        CatalogCircuit created = catalog.createCircuit(input);
        return ResponseEntity.created(URI.create("/api/v1/catalog/circuits/" + created.id())).body(created);
    }
    @PutMapping("/circuits/{id}") public CatalogCircuit updateCircuit(@PathVariable UUID id, @Valid @RequestBody CircuitInput input) {
        return catalog.updateCircuit(id, input);
    }
    @DeleteMapping("/circuits/{id}") public ResponseEntity<Void> archive(@PathVariable UUID id) {
        monitoring.archive(id); return ResponseEntity.noContent().build();
    }
}
