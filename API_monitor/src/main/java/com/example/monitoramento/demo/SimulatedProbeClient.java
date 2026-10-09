package com.example.monitoramento.demo;

import com.example.monitoramento.circuits.CircuitDefinition;
import com.example.monitoramento.probes.ProbeClient;
import com.example.monitoramento.probes.ProbeOutcome;
import com.example.monitoramento.probes.ProbeResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/** Roteiro por tempo: não consulta DNS, IPs ou arquivos institucionais. */
public final class SimulatedProbeClient implements ProbeClient {
    private final Clock clock;
    private final Instant beganAt;
    private final List<CircuitDefinition> circuits;

    public SimulatedProbeClient(Clock clock) {
        this.clock = clock;
        beganAt = clock.instant();
        String[] sites = {"Centro", "Norte", "Sul", "Leste", "Oeste", "Anexo"};
        circuits = IntStream.range(0, 12).mapToObj(index -> {
            String name = "Link " + (index % 2 == 0 ? "principal" : "secundário") + " — " + sites[index / 2];
            return new CircuitDefinition(UUID.nameUUIDFromBytes(("demo-" + index).getBytes(StandardCharsets.UTF_8)),
                    name, "Unidade " + sites[index / 2], "Operadora " + (1 + (index / 2 + index % 2) % 3),
                    index % 2 == 0 ? "PRIMARY" : "SECONDARY");
        }).toList();
    }

    public List<CircuitDefinition> circuits() {
        return circuits;
    }

    @Override
    public ProbeResult probe(CircuitDefinition circuit) {
        int index = circuits.indexOf(circuit);
        if (index < 0) throw new IllegalArgumentException("Circuito fora do roteiro sintético");
        Instant now = clock.instant();
        long phase = Math.floorMod(Duration.between(beganAt, now).toSeconds(), 120);
        if (index == 4 && phase >= 40 && phase < 80) {
            return result(circuit, now, ProbeOutcome.ERROR, null, "Coletor simulado sem observação confiável.");
        }
        boolean failed = (index <= 1 && phase >= 20 && phase < 60)
                || (index == 3 && phase < 40) || index == 10
                || (index == 8 && phase >= 30 && phase < 40);
        if (failed) return result(circuit, now, ProbeOutcome.FAILURE, null, "Destino sintético sem resposta.");
        double latency = index == 6 ? 180 : 15 + index * 1.25 + phase % 7;
        return result(circuit, now, ProbeOutcome.SUCCESS, latency, "Resposta sintética recebida.");
    }

    private ProbeResult result(CircuitDefinition circuit, Instant now, ProbeOutcome outcome,
                               Double latency, String detail) {
        return new ProbeResult(UUID.randomUUID(), circuit.id(), now, now, outcome, latency, "SIMULATED", detail);
    }
}
