package com.example.monitoramento.execution;

import com.example.monitoramento.circuits.CircuitDefinition;
import com.example.monitoramento.probes.ProbeClient;
import com.example.monitoramento.probes.ProbeOutcome;
import com.example.monitoramento.probes.ProbeResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static com.example.monitoramento.execution.BoundedProbeExecutor.Submission.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class BoundedProbeExecutorTest {
    private final Instant at = Instant.parse("2026-10-08T12:00:00Z");
    private final Clock clock = Clock.fixed(at, ZoneOffset.UTC);

    @Test
    void runsDifferentCircuitsInParallelWithoutMixingResults() throws Exception {
        var first = circuit("A");
        var second = circuit("B");
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var delivered = new CountDownLatch(2);
        var results = new ConcurrentHashMap<UUID, ProbeResult>();
        ProbeClient probe = circuit -> {
            entered.countDown();
            release.await();
            return success(circuit, circuit.id().equals(first.id()) ? 0.25 : 73);
        };
        try (var executor = executor(2, 2, Duration.ofSeconds(5))) {
            assertEquals(ACCEPTED, executor.submit(first, probe, result -> { results.put(result.circuitId(), result); delivered.countDown(); }));
            assertEquals(ACCEPTED, executor.submit(second, probe, result -> { results.put(result.circuitId(), result); delivered.countDown(); }));
            try {
                await(entered);
                assertEquals(2, executor.metrics().activeWorkers());
                assertEquals(2, executor.metrics().inFlight());
                assertEquals(ALREADY_RUNNING, executor.submit(first, probe, ignored -> fail("Não deve executar em sobreposição")));
            } finally {
                release.countDown();
            }
            await(delivered);
            assertEquals(0.25, results.get(first.id()).latencyMs());
            assertEquals(73d, results.get(second.id()).latencyMs());
            assertEquals(1, executor.metrics().overlapSkipped());
        }
    }

    @Test
    void rejectsCapacityWithoutExecutingOnTheSubmittingThread() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var delivered = new CountDownLatch(2);
        AtomicInteger rejectedCalls = new AtomicInteger();
        ProbeClient blocked = circuit -> {
            entered.countDown();
            release.await();
            return success(circuit, 10);
        };
        try (var executor = executor(1, 1, Duration.ofSeconds(5))) {
            assertEquals(ACCEPTED, executor.submit(circuit("A"), blocked, ignored -> delivered.countDown()));
            try {
                await(entered);
                assertEquals(ACCEPTED, executor.submit(circuit("B"), blocked, ignored -> delivered.countDown()));
                assertEquals(CAPACITY_REJECTED, executor.submit(circuit("C"), circuit -> {
                    rejectedCalls.incrementAndGet();
                    return success(circuit, 10);
                }, ignored -> fail("Rejeição não é falha do destino")));
                assertEquals(1, executor.metrics().queuedTasks());
                assertEquals(1, executor.metrics().activeWorkers());
                assertEquals(1, executor.metrics().capacityRejected());
                assertEquals(0, rejectedCalls.get());
            } finally {
                release.countDown();
            }
            await(delivered);
        }
    }

    @Test
    void allowsTheCircuitAgainOnlyAfterThePreviousJobHasActuallyFinished() throws Exception {
        var original = circuit("Original");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var sentinelEntered = new CountDownLatch(1);
        var sentinelRelease = new CountDownLatch(1);
        var delivered = new CountDownLatch(3);
        try (var executor = executor(1, 3, Duration.ofSeconds(5))) {
            executor.submit(original, circuit -> { entered.countDown(); release.await(); return success(circuit, 10); }, ignored -> delivered.countDown());
            try {
                await(entered);
                assertEquals(ALREADY_RUNNING, executor.submit(original, circuit -> success(circuit, 20), ignored -> delivered.countDown()));
                executor.submit(circuit("Sentinela"), circuit -> {
                    sentinelEntered.countDown();
                    sentinelRelease.await();
                    return success(circuit, 30);
                }, ignored -> delivered.countDown());
                release.countDown();
                await(sentinelEntered); // O único worker já passou pelo finally da primeira tarefa.
                assertEquals(ACCEPTED, executor.submit(original, circuit -> success(circuit, 20), ignored -> delivered.countDown()));
            } finally {
                release.countDown();
                sentinelRelease.countDown();
            }
            await(delivered);
        }
    }

    @Test
    void timeoutKeepsExclusionUntilAnInterruptIgnoringProbeReallyStops() throws Exception {
        var original = circuit("Lenta");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var delivered = new CountDownLatch(1);
        var results = new CopyOnWriteArrayList<ProbeResult>();
        var executor = executor(1, 2, Duration.ofMillis(100));
        try {
            executor.submit(original, circuit -> {
                entered.countDown();
                while (true) {
                    try { release.await(); break; }
                    catch (InterruptedException ignored) { interrupted.countDown(); }
                }
                return success(circuit, 10);
            }, result -> { results.add(result); delivered.countDown(); });
            await(entered);
            await(delivered);
            await(interrupted);
            assertEquals(ProbeOutcome.ERROR, results.getFirst().outcome());
            assertNull(results.getFirst().latencyMs());
            assertEquals(ALREADY_RUNNING, executor.submit(original, circuit -> success(circuit, 20), ignored -> results.add(ignored)));
            assertEquals(1, executor.metrics().timedOut());
        } finally {
            release.countDown();
            executor.close();
        }
        assertEquals(1, results.size()); // A resposta tardia não sobrescreve o erro já entregue.
        assertEquals(0, executor.metrics().inFlight());
    }

    @Test
    void totalDeadlineIncludesQueueWaitAndDoesNotRunExpiredQueuedProbe() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var delivered = new CountDownLatch(2);
        AtomicInteger queuedCalls = new AtomicInteger();
        var results = new CopyOnWriteArrayList<ProbeResult>();
        var executor = executor(1, 1, Duration.ofMillis(150));
        try {
            executor.submit(circuit("Ocupada"), circuit -> {
                entered.countDown();
                while (true) {
                    try { release.await(); break; }
                    catch (InterruptedException ignored) { /* Mantém o worker ocupado para verificar a fila. */ }
                }
                return success(circuit, 10);
            }, result -> { results.add(result); delivered.countDown(); });
            await(entered);
            assertEquals(ACCEPTED, executor.submit(circuit("Na fila"), circuit -> {
                queuedCalls.incrementAndGet();
                return success(circuit, 10);
            }, result -> { results.add(result); delivered.countDown(); }));
            await(delivered);
        } finally {
            release.countDown();
            executor.close();
        }
        assertEquals(0, queuedCalls.get());
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(result -> result.outcome() == ProbeOutcome.ERROR));
        assertEquals(0, executor.metrics().queuedTasks());
        assertEquals(0, executor.metrics().inFlight());
    }

    @Test
    void mapsExceptionAndIncorrectCircuitIdentityToExecutionErrors() throws Exception {
        var delivered = new CountDownLatch(2);
        var results = new CopyOnWriteArrayList<ProbeResult>();
        var first = circuit("Exceção");
        var second = circuit("Identidade");
        try (var executor = executor(2, 2, Duration.ofSeconds(5))) {
            executor.submit(first, circuit -> { throw new IllegalStateException("Falha de implementação"); },
                    result -> { results.add(result); delivered.countDown(); });
            executor.submit(second, circuit -> success(first, 30),
                    result -> { results.add(result); delivered.countDown(); });
            await(delivered);
        }
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(result -> result.outcome() == ProbeOutcome.ERROR));
        assertEquals(2, results.stream().map(ProbeResult::circuitId).distinct().count());
    }

    @Test
    void shutdownInterruptsJobsAndRefusesNewWork() throws Exception {
        var entered = new CountDownLatch(1);
        var resultReceived = new CountDownLatch(2);
        var results = new CopyOnWriteArrayList<ProbeResult>();
        var executor = executor(1, 1, Duration.ofSeconds(5));
        ProbeClient blocked = circuit -> { entered.countDown(); new CountDownLatch(1).await(); return success(circuit, 10); };
        try {
            executor.submit(circuit("Executando"), blocked, result -> { results.add(result); resultReceived.countDown(); });
            await(entered);
            executor.submit(circuit("Pendente"), blocked, result -> { results.add(result); resultReceived.countDown(); });
        } finally {
            executor.close();
        }
        await(resultReceived);
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(result -> result.outcome() == ProbeOutcome.ERROR));
        assertEquals(CLOSED, executor.submit(circuit("Nova"), circuit -> success(circuit, 10), ignored -> fail("Executor encerrado")));
        assertTrue(executor.metrics().closed());
        assertEquals(0, executor.metrics().inFlight());
    }

    private BoundedProbeExecutor executor(int workers, int queue, Duration timeout) {
        return new BoundedProbeExecutor(workers, queue, timeout, Duration.ofSeconds(2), clock);
    }

    private CircuitDefinition circuit(String name) {
        return new CircuitDefinition(UUID.randomUUID(), name, "Unidade", "Operadora", "PRIMARY");
    }

    private ProbeResult success(CircuitDefinition circuit, double latency) {
        return new ProbeResult(UUID.randomUUID(), circuit.id(), at, at, ProbeOutcome.SUCCESS, latency, "TEST", "Resposta");
    }

    private void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(3, TimeUnit.SECONDS), "O evento esperado não ocorreu dentro do prazo");
    }
}
