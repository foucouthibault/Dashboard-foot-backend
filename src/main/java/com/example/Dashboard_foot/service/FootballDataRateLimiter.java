package com.example.Dashboard_foot.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Limiteur à fenêtre glissante appliqué à chaque appel sortant réel vers
 * football-data.org (le cache court-circuite cette classe sur un hit).
 * Contrairement au cache par clé, cette limite est globale : elle garantit
 * qu'on ne dépasse jamais le quota du plan quel que soit le nombre de
 * combinaisons id/saison/limit distinctes interrogées.
 */
@Component
public class FootballDataRateLimiter {

    private final int maxRequestsPerWindow;
    private final Duration window;
    private final Clock clock;
    private final Deque<Instant> requestTimestamps = new ArrayDeque<>();

    @Autowired
    public FootballDataRateLimiter(
        @Value("${football-data.api.rate-limit-per-minute:10}") int maxRequestsPerWindow
    ) {
        this(maxRequestsPerWindow, Duration.ofMinutes(1), Clock.systemUTC());
    }

    FootballDataRateLimiter(int maxRequestsPerWindow, Duration window, Clock clock) {
        this.maxRequestsPerWindow = maxRequestsPerWindow;
        this.window = window;
        this.clock = clock;
    }

    /**
     * Tente de réserver un slot pour un appel sortant.
     * @return true si l'appel peut partir, false si le quota de la fenêtre est atteint
     */
    public synchronized boolean tryAcquire() {
        Instant now = Instant.now(clock);
        evictExpired(now);
        if (requestTimestamps.size() >= maxRequestsPerWindow) {
            return false;
        }
        requestTimestamps.addLast(now);
        return true;
    }

    /**
     * Nombre de secondes avant qu'un slot ne se libère à nouveau, pour le header Retry-After.
     */
    public synchronized long secondsUntilNextSlot() {
        Instant now = Instant.now(clock);
        evictExpired(now);
        Instant oldest = requestTimestamps.peekFirst();
        if (oldest == null) {
            return 0;
        }
        long millisRemaining = Duration.between(now, oldest.plus(window)).toMillis();
        return Math.max(1, (millisRemaining + 999) / 1000);
    }

    private void evictExpired(Instant now) {
        Instant cutoff = now.minus(window);
        while (!requestTimestamps.isEmpty() && requestTimestamps.peekFirst().isBefore(cutoff)) {
            requestTimestamps.pollFirst();
        }
    }
}
