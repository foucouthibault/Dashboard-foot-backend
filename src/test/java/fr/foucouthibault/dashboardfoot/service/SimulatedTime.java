package fr.foucouthibault.dashboardfoot.service;

import com.github.benmanes.caffeine.cache.Ticker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Temps simulé pour les caches : un {@link Ticker} pour Caffeine (durées de vie) et une
 * {@link Clock} qui avance avec lui (coups d'envoi comparés à l'heure courante).
 */
final class SimulatedTime implements Ticker {

    static final Instant START = Instant.parse("2026-11-19T12:00:00Z");

    private final AtomicLong nanos = new AtomicLong();

    @Override
    public long read() {
        return nanos.get();
    }

    void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    Instant now() {
        return START.plusNanos(nanos.get());
    }

    Clock clock() {
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now();
            }
        };
    }
}
