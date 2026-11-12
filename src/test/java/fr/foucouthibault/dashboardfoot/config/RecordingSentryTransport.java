package fr.foucouthibault.dashboardfoot.config;

import io.sentry.Hint;
import io.sentry.ITransportFactory;
import io.sentry.RequestDetails;
import io.sentry.SentryEnvelope;
import io.sentry.SentryEnvelopeItem;
import io.sentry.SentryEvent;
import io.sentry.SentryItemType;
import io.sentry.SentryOptions;
import io.sentry.transport.ITransport;
import io.sentry.transport.RateLimiter;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Transport Sentry de test : enregistre les événements d'erreur au lieu de les envoyer. Déclaré
 * comme bean, il remplace le transport HTTP du starter : aucun test ne contacte Sentry.
 */
class RecordingSentryTransport implements ITransportFactory {

    /** Un événement reçu, et sa forme JSON telle qu'elle partirait vers Sentry. */
    record Recorded(SentryEvent event, String json) {
    }

    private final List<Recorded> events = new CopyOnWriteArrayList<>();

    List<Recorded> events() {
        return events;
    }

    void clear() {
        events.clear();
    }

    @Override
    public ITransport create(SentryOptions options, RequestDetails requestDetails) {
        return new ITransport() {
            @Override
            public void send(SentryEnvelope envelope, Hint hint) {
                for (SentryEnvelopeItem item : envelope.getItems()) {
                    if (item.getHeader().getType() == SentryItemType.Event) {
                        SentryEvent event = readEvent(options, item);
                        events.add(new Recorded(event, toJson(options, event)));
                    }
                }
            }

            @Override
            public void flush(long timeoutMillis) {
                // Rien à vider : l'envoi est immédiat.
            }

            @Override
            public RateLimiter getRateLimiter() {
                return null;
            }

            @Override
            public void close(boolean isRestarting) {
                // Aucune ressource à libérer.
            }

            @Override
            public void close() {
                // Aucune ressource à libérer.
            }
        };
    }

    private static SentryEvent readEvent(SentryOptions options, SentryEnvelopeItem item) {
        try {
            return item.getEvent(options.getSerializer());
        } catch (Exception e) {
            throw new IllegalStateException("Événement Sentry illisible", e);
        }
    }

    private static String toJson(SentryOptions options, SentryEvent event) {
        try {
            StringWriter writer = new StringWriter();
            options.getSerializer().serialize(event, writer);
            return writer.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
