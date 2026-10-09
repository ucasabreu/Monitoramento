package com.example.monitoramento.probes;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ProbeResultTest {
    private final Instant at = Instant.parse("2026-10-08T12:00:00Z");

    @ParameterizedTest
    @ValueSource(doubles = {0, 0.25, 25})
    void preservesSuccessfulZeroAndSubmillisecondLatency(double latency) {
        ProbeResult result = result(ProbeOutcome.SUCCESS, latency);
        assertEquals(ProbeOutcome.SUCCESS, result.outcome());
        assertEquals(latency, result.latencyMs());
    }

    static Stream<Double> invalidLatencies() {
        return Stream.of(null, -1d, Double.NaN, Double.POSITIVE_INFINITY);
    }

    @ParameterizedTest
    @MethodSource("invalidLatencies")
    void rejectsInvalidSuccessfulLatency(Double latency) {
        assertThrows(IllegalArgumentException.class, () -> result(ProbeOutcome.SUCCESS, latency));
    }

    @Test
    void failureAndExecutionErrorHaveNoResponseLatency() {
        assertNull(result(ProbeOutcome.FAILURE, null).latencyMs());
        assertNull(result(ProbeOutcome.ERROR, null).latencyMs());
        assertThrows(IllegalArgumentException.class, () -> result(ProbeOutcome.FAILURE, 0d));
        assertThrows(IllegalArgumentException.class, () -> result(ProbeOutcome.ERROR, 0d));
    }

    @Test
    void rejectsCompletionBeforeStart() {
        assertThrows(IllegalArgumentException.class, () -> new ProbeResult(UUID.randomUUID(), UUID.randomUUID(),
                at, at.minusSeconds(1), ProbeOutcome.SUCCESS, 1d, "TEST", "Resposta"));
    }

    private ProbeResult result(ProbeOutcome outcome, Double latency) {
        return new ProbeResult(UUID.randomUUID(), UUID.randomUUID(), at, at, outcome, latency, "TEST", "Evidência");
    }
}
