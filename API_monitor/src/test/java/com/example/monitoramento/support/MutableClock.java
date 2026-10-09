package com.example.monitoramento.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

public final class MutableClock extends Clock {
    private final AtomicReference<Instant> instant;
    private final ZoneId zone;

    public MutableClock(Instant instant) {
        this(new AtomicReference<>(instant), ZoneOffset.UTC);
    }

    private MutableClock(AtomicReference<Instant> instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public void set(Instant value) {
        instant.set(value);
    }

    @Override
    public ZoneId getZone() { return zone; }

    @Override
    public Clock withZone(ZoneId value) { return new MutableClock(instant, value); }

    @Override
    public Instant instant() { return instant.get(); }
}
