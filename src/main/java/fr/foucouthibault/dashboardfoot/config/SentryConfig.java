package fr.foucouthibault.dashboardfoot.config;

import io.sentry.SentryOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Branche {@link SentryEventFilter} sur le SDK Sentry, configuré par le starter Spring Boot à
 * partir des propriétés {@code sentry.*}. Sans {@code SENTRY_DSN}, le SDK reste désactivé et ce
 * filtre n'est jamais appelé.
 */
@Configuration
public class SentryConfig {

    @Bean
    public SentryOptions.BeforeSendCallback sentryEventFilter() {
        return new SentryEventFilter();
    }
}
