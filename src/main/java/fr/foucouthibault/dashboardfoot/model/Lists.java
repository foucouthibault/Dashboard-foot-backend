package fr.foucouthibault.dashboardfoot.model;

import java.util.List;

/**
 * Les réponses sont partagées par le cache : leurs listes doivent être immuables. Une liste
 * absente de la réponse amont devient une liste vide.
 */
final class Lists {

    private Lists() {
    }

    static <T> List<T> immutable(List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }
}
