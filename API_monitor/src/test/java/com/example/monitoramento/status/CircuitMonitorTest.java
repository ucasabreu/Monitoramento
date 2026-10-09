package com.example.monitoramento.status;

import com.example.monitoramento.circuits.CircuitDefinition;
import com.example.monitoramento.probes.ProbeOutcome;
import com.example.monitoramento.probes.ProbeResult;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CircuitMonitorTest {
    private final Instant base = Instant.parse("2026-10-08T12:00:00Z");
    private final CircuitDefinition circuit = new CircuitDefinition(UUID.randomUUID(), "Link", "Unidade", "Operadora", "PRIMARY");
    private final MonitoringPolicy policy = new MonitoringPolicy(3, 2, Duration.ofSeconds(32), 100,
            ZoneId.of("America/Sao_Paulo"));
    private final CircuitMonitor monitor = new CircuitMonitor(circuit, policy);

    @Test
    void beginsUnknownAndCountsTheFirstFailedObservation() {
        assertEquals(Availability.UNKNOWN, monitor.snapshot(base).availability());
        failAt(0);
        var snapshot = monitor.snapshot(base);
        assertEquals(1, snapshot.counters().tests());
        assertEquals(1, snapshot.counters().failures());
        assertEquals(1, snapshot.consecutiveFailures());
        assertNull(snapshot.activeIncident());
        assertEquals(Availability.UNKNOWN, snapshot.availability());
    }

    @Test
    void confirmsInitialDownWithoutInventingAnUpToDownTransition() {
        failAt(0);
        failAt(10);
        failAt(20);
        var snapshot = monitor.snapshot(base.plusSeconds(25));
        assertEquals(Availability.DOWN, snapshot.availability());
        assertEquals(3, snapshot.counters().failures());
        assertEquals(0, snapshot.counters().confirmedFalls());
        assertTrue(snapshot.activeIncident().beganWithoutConfirmedUp());
        assertEquals(base, snapshot.activeIncident().firstFailureAt());
        assertEquals(base.plusSeconds(20), snapshot.activeIncident().downConfirmedAt());
        assertEquals(25, snapshot.activeIncident().elapsedSeconds());
    }

    @Test
    void confirmsDownAndRecoveryKeepingBothEvidenceAndConfirmationTimes() {
        successAt(0, 0);
        successAt(10, 0.25);
        failAt(20);
        failAt(30);
        assertEquals(Availability.UP, monitor.snapshot(base.plusSeconds(30)).availability());
        failAt(40);
        UUID incident = monitor.snapshot(base.plusSeconds(40)).activeIncident().id();
        failAt(50);
        assertEquals(incident, monitor.snapshot(base.plusSeconds(50)).activeIncident().id());
        successAt(60, 20);
        var recovering = monitor.snapshot(base.plusSeconds(60));
        assertEquals(Availability.DOWN, recovering.availability());
        assertEquals(base.plusSeconds(60), recovering.activeIncident().firstRecoveryAt());
        assertNull(recovering.activeIncident().recoveryConfirmedAt());
        successAt(70, 20);
        var recovered = monitor.snapshot(base.plusSeconds(70));
        assertEquals(Availability.UP, recovered.availability());
        assertNull(recovered.activeIncident());
        assertEquals(1, recovered.counters().confirmedFalls());
        assertEquals(1, recovered.incidents().size());
        var closed = recovered.incidents().getFirst();
        assertEquals(incident, closed.id());
        assertEquals(base.plusSeconds(20), closed.firstFailureAt());
        assertEquals(base.plusSeconds(40), closed.downConfirmedAt());
        assertEquals(base.plusSeconds(60), closed.firstRecoveryAt());
        assertEquals(base.plusSeconds(70), closed.recoveryConfirmedAt());
        assertEquals(40, closed.elapsedSeconds());
        assertFalse(closed.hasObservationGap());
    }

    @Test
    void isolatedFailureDoesNotOpenIncident() {
        successAt(0, 20);
        successAt(10, 20);
        failAt(20);
        successAt(30, 20);
        var snapshot = monitor.snapshot(base.plusSeconds(30));
        assertEquals(Availability.UP, snapshot.availability());
        assertEquals(Quality.GOOD, snapshot.quality());
        assertEquals(1, snapshot.counters().failures());
        assertTrue(snapshot.incidents().isEmpty());
    }

    @Test
    void distinguishesDegradedQualityFromAvailability() {
        successAt(0, 180);
        successAt(10, 180);
        var snapshot = monitor.snapshot(base.plusSeconds(10));
        assertEquals(Availability.UP, snapshot.availability());
        assertEquals(Quality.DEGRADED, snapshot.quality());
        assertEquals(0, snapshot.counters().failures());
        assertTrue(snapshot.incidents().isEmpty());
    }

    @Test
    void staleEvidenceExpiresAtTheConfiguredBoundary() {
        successAt(0, 20);
        successAt(10, 20);
        assertEquals(Availability.UP, monitor.snapshot(base.plusSeconds(41)).availability());
        var stale = monitor.snapshot(base.plusSeconds(42));
        assertEquals(Availability.UNKNOWN, stale.availability());
        assertEquals(Quality.UNKNOWN, stale.quality());
        assertEquals(32, stale.dataAgeSeconds());
        assertEquals(0, stale.consecutiveSuccesses());
    }

    @Test
    void executionErrorIsNotCountedAsDestinationFailure() {
        successAt(0, 20);
        successAt(10, 20);
        record(base.plusSeconds(20), ProbeOutcome.ERROR, null);
        var snapshot = monitor.snapshot(base.plusSeconds(20));
        assertEquals(Availability.UNKNOWN, snapshot.availability());
        assertEquals(2, snapshot.counters().tests());
        assertEquals(0, snapshot.counters().failures());
        assertEquals(1, snapshot.counters().executionErrors());
        assertEquals(base.plusSeconds(10), snapshot.lastReliableObservationAt());
        assertTrue(snapshot.incidents().isEmpty());
    }

    @Test
    void observationGapDoesNotCreateAnExtraIncidentOrPretendContinuousDowntime() {
        failAt(0);
        failAt(10);
        failAt(20);
        UUID id = monitor.snapshot(base.plusSeconds(20)).activeIncident().id();
        assertEquals(Availability.UNKNOWN, monitor.snapshot(base.plusSeconds(52)).availability());
        successAt(100, 20);
        successAt(110, 20);
        var closed = monitor.snapshot(base.plusSeconds(110)).incidents().getFirst();
        assertEquals(id, closed.id());
        assertTrue(closed.hasObservationGap());
        assertEquals(Availability.UP, monitor.snapshot(base.plusSeconds(110)).availability());
    }

    @Test
    void recognizesGapEvenWithoutAnHttpQueryBetweenMeasurements() {
        failAt(0);
        failAt(10);
        failAt(20);
        successAt(100, 20);
        successAt(110, 20);
        assertTrue(monitor.snapshot(base.plusSeconds(110)).incidents().getFirst().hasObservationGap());
    }

    @Test
    void duplicatedAndOutOfOrderResultsDoNotChangeCountersOrCurrentState() {
        ProbeResult first = result(base, ProbeOutcome.SUCCESS, 20d);
        assertEquals(CircuitMonitor.Recording.ACCEPTED, monitor.record(first, base));
        assertEquals(CircuitMonitor.Recording.DUPLICATE, monitor.record(first, base.plusSeconds(10)));
        successAt(10, 20);
        var old = result(base.plusSeconds(5), ProbeOutcome.FAILURE, null);
        assertEquals(CircuitMonitor.Recording.OUT_OF_ORDER, monitor.record(old, base.plusSeconds(20)));
        var snapshot = monitor.snapshot(base.plusSeconds(20));
        assertEquals(2, snapshot.counters().tests());
        assertEquals(Availability.UP, snapshot.availability());
        assertEquals(base.plusSeconds(10), snapshot.latestResult().completedAt());
    }

    @Test
    void monthStatisticsUseYearAndReportingTimezoneAndChangeAtRollover() {
        Instant lastYear = Instant.parse("2025-10-08T12:00:00Z");
        monitor.record(result(lastYear, ProbeOutcome.FAILURE, null), base);
        assertEquals(0, monitor.snapshot(base).counters().monthlyTests());
        Instant beforeLocalNovember = Instant.parse("2026-11-01T02:59:50Z");
        monitor.record(result(beforeLocalNovember, ProbeOutcome.FAILURE, null), beforeLocalNovember);
        assertEquals("2026-10", monitor.snapshot(beforeLocalNovember).counters().reportingMonth());
        assertEquals(1, monitor.snapshot(beforeLocalNovember).counters().monthlyTests());
        Instant localNovember = Instant.parse("2026-11-01T03:00:00Z");
        assertEquals(0, monitor.snapshot(localNovember).counters().monthlyTests());
        monitor.record(result(localNovember, ProbeOutcome.SUCCESS, 20d), localNovember);
        assertEquals("2026-11", monitor.snapshot(localNovember).counters().reportingMonth());
        assertEquals(1, monitor.snapshot(localNovember).counters().monthlyTests());
        assertEquals(0, monitor.snapshot(localNovember).counters().monthlyFailures());
    }

    @Test
    void incidentsCanCrossCalendarYearWithoutNegativeOrResetDuration() {
        Instant at = Instant.parse("2026-12-31T23:59:40Z");
        for (int seconds : new int[] {0, 10, 20}) {
            monitor.record(result(at.plusSeconds(seconds), ProbeOutcome.FAILURE, null), at.plusSeconds(seconds));
        }
        var incident = monitor.snapshot(at.plusSeconds(30)).activeIncident();
        assertEquals(at, incident.firstFailureAt());
        assertEquals(30, incident.elapsedSeconds());
    }

    @Test
    void rejectsResultsForAnotherCircuitAndFutureMeasurements() {
        var other = new ProbeResult(UUID.randomUUID(), UUID.randomUUID(), base, base,
                ProbeOutcome.SUCCESS, 0d, "TEST", "Resposta");
        assertThrows(IllegalArgumentException.class, () -> monitor.record(other, base));
        assertThrows(IllegalArgumentException.class, () -> monitor.record(result(base.plusSeconds(1), ProbeOutcome.SUCCESS, 0d), base));
        assertEquals(0, monitor.snapshot(base).counters().tests());
    }

    @Test
    void concurrentIngestionDoesNotLoseCountersAndAllowsDistinctObservationsAtTheSameInstant() throws Exception {
        var entered = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        var results = new ArrayList<Future<CircuitMonitor.Recording>>();
        try (var executor = Executors.newFixedThreadPool(4)) {
            try {
                for (int index = 0; index < 100; index++) {
                    results.add(executor.submit(() -> {
                        entered.countDown();
                        release.await();
                        return monitor.record(result(base, ProbeOutcome.SUCCESS, 0.25), base);
                    }));
                }
                assertTrue(entered.await(3, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
            for (var result : results) assertEquals(CircuitMonitor.Recording.ACCEPTED, result.get(3, TimeUnit.SECONDS));
        }
        var snapshot = monitor.snapshot(base);
        assertEquals(100, snapshot.counters().tests());
        assertEquals(100, snapshot.counters().successes());
        assertEquals(100, snapshot.counters().monthlyTests());
        assertEquals(Availability.UP, snapshot.availability());
    }

    private void successAt(long seconds, double latency) { record(base.plusSeconds(seconds), ProbeOutcome.SUCCESS, latency); }
    private void failAt(long seconds) { record(base.plusSeconds(seconds), ProbeOutcome.FAILURE, null); }
    private void record(Instant at, ProbeOutcome outcome, Double latency) { monitor.record(result(at, outcome, latency), at); }
    private ProbeResult result(Instant at, ProbeOutcome outcome, Double latency) {
        return new ProbeResult(UUID.randomUUID(), circuit.id(), at, at, outcome, latency, "TEST", "Evidência de teste");
    }
}
