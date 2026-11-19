package fr.foucouthibault.dashboardfoot.service;

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
 *
 * Le quota est partagé entre deux catégories ({@link QuotaCategory}) : les saisons terminées
 * n'ont droit qu'à une part de la fenêtre ({@code historicalMax}), pour qu'un visiteur qui
 * parcourt les saisons passées ne prive pas de données les visiteurs de la saison en cours ;
 * la saison en cours peut utiliser tout le quota restant. Le total reste toujours borné par
 * {@code maxRequestsPerWindow}.
 */
@Component
public class FootballDataRateLimiter {

    private record Slot(Instant at, QuotaCategory category) {
    }

    private final int maxRequestsPerWindow;
    private final int historicalMax;
    private final Duration window;
    private final Clock clock;
    private final Deque<Slot> slots = new ArrayDeque<>();

    @Autowired
    public FootballDataRateLimiter(
        @Value("${football-data.api.rate-limit-per-minute:10}") int maxRequestsPerWindow,
        @Value("${football-data.api.rate-limit-historical-per-minute:3}") int historicalMax
    ) {
        this(maxRequestsPerWindow, historicalMax, Duration.ofMinutes(1), Clock.systemUTC());
    }

    FootballDataRateLimiter(int maxRequestsPerWindow, int historicalMax, Duration window, Clock clock) {
        this.maxRequestsPerWindow = maxRequestsPerWindow;
        // La part des saisons terminées ne peut pas dépasser le total.
        this.historicalMax = Math.clamp(historicalMax, 0, maxRequestsPerWindow);
        this.window = window;
        this.clock = clock;
    }

    /**
     * Tente de réserver un slot pour un appel sortant de la catégorie donnée.
     * @return true si l'appel peut partir, false si le quota de la fenêtre (total, ou part des
     * saisons terminées) est atteint
     */
    public synchronized boolean tryAcquire(QuotaCategory category) {
        Instant now = Instant.now(clock);
        evictExpired(now);
        if (slots.size() >= maxRequestsPerWindow) {
            return false;
        }
        if (category == QuotaCategory.HISTORICAL && historicalCount() >= historicalMax) {
            return false;
        }
        slots.addLast(new Slot(now, category));
        return true;
    }

    /**
     * Nombre de secondes avant qu'un slot ne se libère pour cette catégorie, pour l'en-tête
     * Retry-After ; 0 si un appel peut partir tout de suite.
     */
    public synchronized long secondsUntilNextSlot(QuotaCategory category) {
        Instant now = Instant.now(clock);
        evictExpired(now);
        Instant freedAt;
        if (category == QuotaCategory.HISTORICAL && historicalCount() >= historicalMax) {
            // L'expiration du plus ancien appel de saison terminée libère à la fois une place
            // dans la part et une place dans le total.
            freedAt = slots.stream()
                .filter(slot -> slot.category() == QuotaCategory.HISTORICAL)
                .findFirst()
                .map(Slot::at)
                .orElse(null);
            if (freedAt == null) {
                // Part nulle (historicalMax = 0) : aucun créneau ne se libérera.
                return window.toSeconds();
            }
        } else if (slots.size() >= maxRequestsPerWindow) {
            Slot oldest = slots.peekFirst();
            if (oldest == null) {
                // Quota nul : aucun créneau ne se libérera.
                return window.toSeconds();
            }
            freedAt = oldest.at();
        } else {
            return 0;
        }
        long millisRemaining = Duration.between(now, freedAt.plus(window)).toMillis();
        return Math.max(1, (millisRemaining + 999) / 1000);
    }

    private long historicalCount() {
        return slots.stream().filter(slot -> slot.category() == QuotaCategory.HISTORICAL).count();
    }

    private void evictExpired(Instant now) {
        Instant cutoff = now.minus(window);
        while (!slots.isEmpty() && slots.peekFirst().at().isBefore(cutoff)) {
            slots.pollFirst();
        }
    }
}
