package com.example.monitoramento.demo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("monitoring")
public record MonitoringProperties(@Min(1) int failureThreshold, @Min(1) int recoveryThreshold,
                                   @NotNull Duration observationTtl, @Positive double degradedLatencyMs,
                                   @NotBlank String reportingZone, @Min(1) int workers,
                                   @Min(1) int queueCapacity, @NotNull Duration probeTimeout,
                                   @NotNull Duration shutdownTimeout, @Valid @NotNull Demo demo) {
    public record Demo(boolean enabled, @NotNull Duration interval) {
        public Demo {
            if (interval != null && interval.toMillis() < 1) {
                throw new IllegalArgumentException("Intervalo mínimo de demonstração: 1 ms");
            }
        }
    }
}
