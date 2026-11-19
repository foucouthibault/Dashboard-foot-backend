package fr.foucouthibault.dashboardfoot.service;

import java.time.Duration;

/** Permet aux tests d'autres paquets de simuler une réponse servie depuis la copie de secours. */
public final class StaleResponseTestSupport {

    private StaleResponseTestSupport() {
    }

    public static void markCurrentRequest(Duration age) {
        StaleResponse.markCurrentRequest(age);
    }
}
