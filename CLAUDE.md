# CLAUDE.md — Dashboard Foot (backend)

Proxy Spring Boot 4 / Java 25 vers football-data.org v4, consommé par le frontend Vue
(dépôt `foucouthibault/Dashboard-foot`, cloné à côté dans `../Dashboard-foot`).
Le README décrit les routes, les choix techniques et la configuration : le lire avant
de modifier le comportement du service.

## Commandes

```bash
./mvnw test                        # tests unitaires (sans clé d'API, sans réseau)
./mvnw verify                      # tests + rapport de couverture JaCoCo (target/site/jacoco)
FOOTBALL_DATA_API_TOKEN=... ./mvnw spring-boot:run   # API sur http://localhost:8080
```

Toujours utiliser `./mvnw`, pas un `mvn` global.

## Architecture

- `controller/CompetitionProxyController` : valide `id` (`^[a-zA-Z0-9_-]+$`) et `season`
  (4 chiffres), puis délègue. Aucune logique métier ici ; une entrée invalide lève
  `InvalidRequestException`.
- `controller/ApiExceptionHandler` : traduit les exceptions du paquet `exception` en
  réponses HTTP (`{"error": "..."}` en JSON, ou erreur amont relayée telle quelle).
- `service/FootballDataProxyService` : cache, appel HTTP, désérialisation en records,
  erreurs par exceptions, contrôle SSRF, découpage des buteurs.
- `model/` : records des réponses (`CompetitionResponse`, `StandingsResponse`,
  `MatchesResponse`, `ScorersResponse`), limités aux champs lus par le frontend. Listes
  immuables : les instances sont partagées par le cache.
- `service/FootballDataRateLimiter` : quota global à fenêtre glissante, `Clock` injectable.
- `config/CacheConfig` : trois caches Caffeine (statique 60 s, saison en cours 5 min,
  saison terminée 24 h).

## Invariants à ne pas casser

- **Quota football-data.org : 10 appels/minute pour tout le monde.** Tout appel sortant
  passe par `rateLimiter.tryAcquire()` ; un hit de cache ne doit jamais le consommer.
  Toute nouvelle route doit passer par `cached(...)`.
- **Seules les réponses 2xx sont mises en cache.**
- **Les buteurs sont toujours demandés avec `limit=50`** puis découpés localement :
  le `limit` du client ne fait pas partie de la clé de cache.
- **Saison terminée = année < année civile en cours** (`isHistoricalSeason`). Le frontend
  calcule ses saisons de la même façon : changer l'un implique de vérifier l'autre.
- **Sécurité** : le jeton reste côté serveur ; seuls `Accept` et `Accept-Language` sont
  relayés ; l'URL sortante doit garder le schéma, l'hôte et le port de
  `football-data.api.base-url` (`isAllowedApiUri`) ; une exception réseau donne un `502`
  sans exposer son message.
- **Les tests ne touchent jamais la vraie API** : `RestTemplate` est mocké avec Mockito et
  le temps est simulé via `Clock`. Ne pas ajouter de test qui consomme le quota.

## Contrat avec le frontend

Les 4 routes `GET /api/competitions/{id}[/standings|/matches|/scorers]` renvoient la
structure JSON de football-data.org réduite aux champs des records de `model/`, qui doivent
rester alignés sur `../Dashboard-foot/src/types/index.ts` : un champ dont le frontend a
besoin s'ajoute des deux côtés. Une fonctionnalité qui change une route ou un
paramètre se fait avec une branche dans chaque dépôt et deux PR qui se référencent.

## Travailler à partir d'une issue

- Les tâches sont des issues GitHub (`gh issue list`), une par PR, avec des critères de fin.
  Étiquettes : `qualite`, `deploiement`, `tests`, `refacto`, `outillage`,
  `fonctionnalite` (optionnel), `vitrine` (présentation du projet, en pause).
- Ordre de traitement : les issues sont rangées dans des jalons numérotés
  (« 1 – Filet de sécurité », « 2 – Backend propre », « 3 – Mise en ligne »…), à traiter
  dans l'ordre. La prochaine tâche est une issue ouverte du plus petit jalon qui n'est
  bloquée par aucune autre : `gh issue list --milestone "<titre>"`. Les jalons « optionnel »
  et « en pause » ne se traitent que sur demande.
- Avant de commencer : lire l'issue (`gh issue view <n>`) et vérifier qu'aucune issue ne la
  bloque encore (liens « bloqué par » de GitHub, y compris vers l'autre dépôt :
  `gh api repos/foucouthibault/<dépôt>/issues/<n>/dependencies/blocked_by`).
- La PR reprend les critères de fin cochés et contient `Closes #<n>` pour fermer l'issue
  au merge. Un critère non rempli est signalé dans la PR, pas coché.

## Conventions

- Code, commentaires, messages de log, commits et PR en **français**.
- Commits à l'impératif présent, sans préfixe : « Ajoute … », « Corrige … », « Épingle … ».
- Jamais de push sur `main` : une branche (`feat/…`, `fix/…`, `chore/…`, `docs/…`) et une PR.
- Injection par constructeur ; les propriétés configurables vont dans
  `application.properties` avec une valeur par défaut, et dans le tableau du README.
- Tout changement de comportement s'accompagne d'un test.

## CI et qualité

- `.github/workflows/maven.yml` : `mvn verify` puis analyse SonarQube Cloud
  (projet `foucouthibault_Dashboard-foot-backend`, quality gate bloquant sur la PR).
- Toute nouvelle action GitHub tierce est épinglée sur un SHA complet avec la version
  en commentaire (règle Sonar S7637), même si certains workflows existants utilisent
  encore des tags.
- Résultats Sonar lisibles sans token (projet public) :
  `curl "https://sonarcloud.io/api/issues/search?componentKeys=foucouthibault_Dashboard-foot-backend&resolved=false"`
  (ajouter `&pullRequest=<n>` pour une PR).
- Avant de déclarer une PR prête : `./mvnw verify` passe en local, puis `gh pr checks <n>`.

## Ne jamais committer

- `FOOTBALL_DATA_API_TOKEN` ou toute autre clé (les fichiers `.env` sont ignorés).
- `target/`, `.mcp.json`, `.claude/settings.local.json`.
