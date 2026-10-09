package com.example.monitoramento.status;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Objects;

public record MonitoringPolicy(int failureThreshold, int recoveryThreshold, Duration observationTtl,
                               double degradedLatencyMs, ZoneId reportingZone) {
    public MonitoringPolicy {
        Objects.requireNonNull(observationTtl, "observationTtl");
        Objects.requireNonNull(reportingZone, "reportingZone");
        if (failureThreshold < 1 || recoveryThreshold < 1 || observationTtl.isNegative()
                || observationTtl.isZero() || !Double.isFinite(degradedLatencyMs) || degradedLatencyMs <= 0) {
            throw new IllegalArgumentException("Política de monitoramento inválida");
        }
    }
}
