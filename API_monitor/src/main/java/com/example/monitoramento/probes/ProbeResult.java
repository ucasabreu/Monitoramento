package com.example.monitoramento.probes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Resultado imutável: zero milissegundos continua sendo uma latência válida. */
public record ProbeResult(UUID id, UUID circuitId, Instant startedAt, Instant completedAt,
                          ProbeOutcome outcome, Double latencyMs, String source, String detail) {
    public ProbeResult {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(circuitId, "circuitId");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        Objects.requireNonNull(outcome, "outcome");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("Fim da sonda anterior ao início");
        }
        if (source == null || source.isBlank() || detail == null || detail.isBlank()) {
            throw new IllegalArgumentException("Origem e evidência são obrigatórias");
        }
        if (outcome == ProbeOutcome.SUCCESS) {
            if (latencyMs == null || !Double.isFinite(latencyMs) || latencyMs < 0) {
                throw new IllegalArgumentException("Sucesso exige latência finita não negativa");
            }
        } else if (latencyMs != null) {
            throw new IllegalArgumentException("Falha ou erro não possui latência de resposta");
        }
    }
}
