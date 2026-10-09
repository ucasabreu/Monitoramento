package com.example.monitoramento.demo;

import com.example.monitoramento.execution.BoundedProbeExecutor;
import com.example.monitoramento.status.MonitoringPolicy;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("demo")
public class MonitoringConfiguration {
    @Bean
    Clock monitoringClock() {
        return Clock.systemUTC();
    }

    @Bean
    MonitoringPolicy monitoringPolicy(MonitoringProperties properties) {
        return new MonitoringPolicy(properties.failureThreshold(), properties.recoveryThreshold(),
                properties.observationTtl(), properties.degradedLatencyMs(),
                ZoneId.of(properties.reportingZone()));
    }

    @Bean(destroyMethod = "close")
    BoundedProbeExecutor probeExecutor(MonitoringProperties properties, Clock clock) {
        return new BoundedProbeExecutor(properties.workers(), properties.queueCapacity(),
                properties.probeTimeout(), properties.shutdownTimeout(), clock);
    }
}
