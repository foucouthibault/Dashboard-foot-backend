package fr.foucouthibault.dashboardfoot.config;

import fr.foucouthibault.dashboardfoot.model.MatchesResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * Durée de vie des données de la saison en cours d'une compétition, déduite de ses matchs : ces
 * données ne changent que lorsqu'un match se joue.
 * <ul>
 *   <li>un match est en cours ({@code IN_PLAY}, {@code PAUSED}), ou son coup d'envoi est passé
 *   depuis peu alors que son statut n'a pas encore changé : durée courte ({@code live}) ;</li>
 *   <li>sinon : jusqu'au prochain coup d'envoi, bornée par un plancher et un plafond ; sans match
 *   à venir (intersaison), le plafond.</li>
 * </ul>
 * Le calcul ne lit que la réponse déjà chargée : il ne déclenche jamais d'appel à football-data.org.
 */
final class MatchAwareTtl {

    private static final Set<String> LIVE_STATUSES = Set.of("IN_PLAY", "PAUSED");
    private static final Set<String> UPCOMING_STATUSES = Set.of("SCHEDULED", "TIMED");
    /**
     * Un match dont le coup d'envoi est passé depuis moins longtemps que ça, mais encore annoncé
     * « à venir », est sans doute en cours (statut amont en retard). Au-delà, c'est une donnée
     * figée (match reporté sans changement de statut) qui ne doit pas garder le cache au plus court.
     */
    private static final Duration LATE_STATUS_WINDOW = Duration.ofHours(3);

    private final Duration live;
    private final Duration idleMin;
    private final Duration idleMax;
    private final Clock clock;

    MatchAwareTtl(Duration live, Duration idleMin, Duration idleMax, Clock clock) {
        this.live = live;
        this.idleMin = idleMin;
        this.idleMax = idleMax.compareTo(idleMin) < 0 ? idleMin : idleMax;
        this.clock = clock;
    }

    Duration ttlFor(MatchesResponse response) {
        Instant now = clock.instant();
        Instant nextKickoff = null;
        for (MatchesResponse.Match match : response.matches()) {
            if (match.status() == null) {
                continue;
            }
            if (LIVE_STATUSES.contains(match.status())) {
                return live;
            }
            if (UPCOMING_STATUSES.contains(match.status())) {
                Instant kickoff = parse(match.utcDate());
                if (kickoff == null) {
                    continue;
                }
                if (!kickoff.isAfter(now)) {
                    if (Duration.between(kickoff, now).compareTo(LATE_STATUS_WINDOW) < 0) {
                        return live;
                    }
                } else if (nextKickoff == null || kickoff.isBefore(nextKickoff)) {
                    nextKickoff = kickoff;
                }
            }
        }
        if (nextKickoff == null) {
            return idleMax;
        }
        return clamp(Duration.between(now, nextKickoff));
    }

    private Duration clamp(Duration untilKickoff) {
        if (untilKickoff.compareTo(idleMin) < 0) {
            return idleMin;
        }
        return untilKickoff.compareTo(idleMax) > 0 ? idleMax : untilKickoff;
    }

    private static Instant parse(String utcDate) {
        if (utcDate == null) {
            return null;
        }
        try {
            return Instant.parse(utcDate);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
