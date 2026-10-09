package com.example.monitoramento.execution;

import com.example.monitoramento.circuits.CircuitDefinition;
import com.example.monitoramento.probes.ProbeClient;
import com.example.monitoramento.probes.ProbeOutcome;
import com.example.monitoramento.probes.ProbeResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fila e workers limitados; o prazo inclui a espera na fila. */
public final class BoundedProbeExecutor implements AutoCloseable {
    public enum Submission { ACCEPTED, ALREADY_RUNNING, CAPACITY_REJECTED, CLOSED }
    public record Metrics(int activeWorkers, int queuedTasks, int inFlight, long accepted,
                          long overlapSkipped, long capacityRejected, long timedOut,
                          long processingErrors, boolean closed) {}

    private static final Logger log = LoggerFactory.getLogger(BoundedProbeExecutor.class);
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor deadlines;
    private final ConcurrentHashMap<UUID, Job> inFlight = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration timeout;
    private final Duration shutdownTimeout;
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong overlapSkipped = new AtomicLong();
    private final AtomicLong capacityRejected = new AtomicLong();
    private final AtomicLong timedOut = new AtomicLong();
    private final AtomicLong processingErrors = new AtomicLong();
    private boolean closed;

    public BoundedProbeExecutor(int workerCount, int queueCapacity, Duration timeout,
                                Duration shutdownTimeout, Clock clock) {
        if (workerCount < 1 || queueCapacity < 1 || timeout.isNegative() || timeout.isZero()
                || shutdownTimeout.isNegative() || shutdownTimeout.isZero()) {
            throw new IllegalArgumentException("Limites de execução inválidos");
        }
        this.clock = Objects.requireNonNull(clock);
        this.timeout = timeout;
        this.shutdownTimeout = shutdownTimeout;
        workers = new ThreadPoolExecutor(workerCount, workerCount, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), threads("monitor-probe-"),
                new ThreadPoolExecutor.AbortPolicy());
        deadlines = new ScheduledThreadPoolExecutor(1, threads("monitor-deadline-"));
        deadlines.setRemoveOnCancelPolicy(true);
    }

    public synchronized Submission submit(CircuitDefinition circuit, ProbeClient client,
                                          Consumer<ProbeResult> onResult) {
        Objects.requireNonNull(circuit);
        Objects.requireNonNull(client);
        Objects.requireNonNull(onResult);
        if (closed) return Submission.CLOSED;
        Job job = new Job(circuit, client, onResult);
        if (inFlight.putIfAbsent(circuit.id(), job) != null) {
            overlapSkipped.incrementAndGet();
            return Submission.ALREADY_RUNNING;
        }
        try {
            job.deadline = deadlines.schedule(job::timeout, timeout.toNanos(), TimeUnit.NANOSECONDS);
            workers.execute(job);
            accepted.incrementAndGet();
            return Submission.ACCEPTED;
        } catch (RejectedExecutionException e) {
            job.discard();
            capacityRejected.incrementAndGet();
            return Submission.CAPACITY_REJECTED;
        }
    }

    public synchronized Metrics metrics() {
        return new Metrics(workers.getActiveCount(), workers.getQueue().size(), inFlight.size(),
                accepted.get(), overlapSkipped.get(), capacityRejected.get(), timedOut.get(), processingErrors.get(), closed);
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        inFlight.values().forEach(job -> job.abort("Execução interrompida pelo encerramento do monitor."));
        workers.shutdownNow();
        deadlines.shutdownNow();
        try {
            if (!workers.awaitTermination(shutdownTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                log.warn("Há sondas que ainda não responderam à interrupção de encerramento");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory threads(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private final class Job implements Runnable {
        private final CircuitDefinition circuit;
        private final ProbeClient client;
        private final Consumer<ProbeResult> receiver;
        private final Instant submittedAt = clock.instant();
        private final AtomicInteger state = new AtomicInteger(); // 0: fila, 1: execução, 2: encerrada
        private final AtomicBoolean delivered = new AtomicBoolean();
        private Thread owner; // Protegida pelo monitor desta Job, inclusive antes de interromper.
        private volatile ScheduledFuture<?> deadline;

        private Job(CircuitDefinition circuit, ProbeClient client, Consumer<ProbeResult> receiver) {
            this.circuit = circuit;
            this.client = client;
            this.receiver = receiver;
        }

        @Override
        public void run() {
            if (!state.compareAndSet(0, 1)) return;
            synchronized (this) {
                owner = Thread.currentThread();
            }
            try {
                if (delivered.get()) return;
                ProbeResult result = client.probe(circuit);
                if (result == null || !circuit.id().equals(result.circuitId())) {
                    throw new IllegalArgumentException("Sonda devolveu resultado de outro circuito");
                }
                if (delivered.compareAndSet(false, true)) receive(result);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                if (delivered.compareAndSet(false, true)) {
                    receive(error("Falha de execução da sonda: " + e.getClass().getSimpleName()));
                }
            } finally {
                synchronized (this) {
                    owner = null;
                    state.set(2);
                    cancelDeadline();
                    inFlight.remove(circuit.id(), this);
                }
            }
        }

        private void timeout() {
            if (delivered.compareAndSet(false, true)) {
                timedOut.incrementAndGet();
                receive(error("Prazo total da execução excedido; não há evidência confiável do destino."));
                stopWork();
            }
        }

        private void abort(String reason) {
            if (delivered.compareAndSet(false, true)) receive(error(reason));
            cancelDeadline();
            stopWork();
        }

        private synchronized void stopWork() {
            if (state.compareAndSet(0, 2)) {
                workers.remove(this);
                inFlight.remove(circuit.id(), this);
            } else if (owner != null) {
                owner.interrupt();
                // Mantém a exclusão até a sonda em execução realmente sair do finally.
            }
        }

        private void discard() {
            delivered.set(true);
            cancelDeadline();
            state.compareAndSet(0, 2);
            inFlight.remove(circuit.id(), this);
        }

        private void cancelDeadline() {
            ScheduledFuture<?> future = deadline;
            if (future != null) future.cancel(false);
        }

        private ProbeResult error(String detail) {
            Instant now = clock.instant();
            return new ProbeResult(UUID.randomUUID(), circuit.id(), submittedAt,
                    now.isBefore(submittedAt) ? submittedAt : now,
                    ProbeOutcome.ERROR, null, "EXECUTOR", detail);
        }

        private void receive(ProbeResult result) {
            try {
                receiver.accept(result);
            } catch (RuntimeException e) {
                processingErrors.incrementAndGet();
                log.error("Falha ao processar resultado do circuito {}", circuit.id(), e);
            }
        }
    }
}
