# ⚽ Dashboard Foot — Backend

API Spring Boot qui relaie [football-data.org](https://www.football-data.org/) pour le frontend [Dashboard Foot](https://github.com/foucouthibault/Dashboard-foot). Elle garde la clé d'API côté serveur, met les réponses en cache et fait respecter le quota de l'offre gratuite (10 requêtes par minute).

[![Java CI with Maven](https://github.com/foucouthibault/Dashboard-foot-backend/actions/workflows/maven.yml/badge.svg)](https://github.com/foucouthibault/Dashboard-foot-backend/actions/workflows/maven.yml)
![Java](https://img.shields.io/badge/Java-25-f89820?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6db33f?logo=springboot)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)

## Architecture

```mermaid
flowchart LR
    F["Frontend Vue 3"] -- "GET /api/competitions/..." --> C["CompetitionProxyController<br/>validation des paramètres"]
    C --> S["FootballDataProxyService"]
    S -- "hit" --> K[("Cache Caffeine<br/>3 durées de vie")]
    S -- "miss" --> R["FootballDataRateLimiter<br/>10 req/min"]
    R -- "X-Auth-Token" --> API["football-data.org v4"]
```

## Endpoints

| Méthode | Route | Paramètres | Cache |
|---|---|---|---|
| `GET` | `/api/competitions/{id}` | — | 60 s |
| `GET` | `/api/competitions/{id}/standings` | `season` (optionnel, ex. `2025`) | 5 min, ou 24 h pour une saison terminée |
| `GET` | `/api/competitions/{id}/matches` | — | 60 s |
| `GET` | `/api/competitions/{id}/scorers` | `limit`, `season` (optionnels) | 5 min, ou 24 h pour une saison terminée |

`{id}` accepte un code (`FL1`, `PL`…) ou un identifiant numérique (`2015`). Un `id` qui ne correspond pas à `^[a-zA-Z0-9_-]+$` ou une `season` qui n'est pas une année sur 4 chiffres renvoient `400`.

Un endpoint de santé, `GET /actuator/health`, répond `{"status":"UP"}` pour le contrôle de l'hébergeur. Il n'appelle jamais football-data.org et ne consomme donc pas de quota. Aucun autre endpoint Actuator n'est exposé. Les métriques (Micrometer) sont exclues de la dépendance Actuator : elles ne servent pas et apportaient HdrHistogram 2.2.2, touché par CVE-2026-14686 sans version corrigée.

Le contrat complet (routes, paramètres, schémas des réponses et des erreurs) est décrit en OpenAPI 3 :

- Swagger UI : http://localhost:8080/swagger-ui.html ;
- spécification : http://localhost:8080/v3/api-docs ;
- copie versionnée : [`openapi.json`](./openapi.json), d'où le frontend tire ses types.

Les réponses reprennent la structure de football-data.org, réduite aux champs qu'affiche le frontend (records Java du paquet `model`, alignés sur `src/types/index.ts` du frontend). Les autres champs de l'API sont ignorés. Les erreurs sont des `ProblemDetail` ([RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)), toujours en `application/problem+json` : `{"title": "Bad Request", "status": 400, "detail": "Format de saison invalide"}`.

## Choix techniques

- **Un quota global, pas par visiteur.** `FootballDataRateLimiter` applique une fenêtre glissante d'une minute à tous les appels sortants réels. Quand le quota est atteint, l'API répond `429` avec un en-tête `Retry-After` au lieu de se faire bloquer par football-data.org. Les réponses servies depuis le cache ne comptent pas dans le quota.
- **Un cache adapté à chaque type de donnée** (`CacheConfig`) :
  - 60 s pour les compétitions et les matchs ;
  - 5 min pour les classements et buteurs de la saison en cours ;
  - 24 h pour les saisons terminées, qui ne changent plus.
- **Seules les réponses réussies sont mises en cache.** Une erreur passagère (`429`, panne réseau) ne reste pas figée jusqu'à la fin de la durée de vie du cache.
- **Un seul appel pour tous les `limit` de buteurs.** Le service demande toujours 50 buteurs à l'API puis découpe la liste selon le `limit` demandé. Changer de `limit` côté frontend ne consomme donc jamais de quota.
- **Des réponses typées.** Le service désérialise la réponse de l'API dans des records, met ces objets en cache et découpe la liste des buteurs sans repasser par le JSON. Une réponse réussie mais vide ou illisible est traitée comme une panne (`502`) et n'est pas mise en cache.
- **Des erreurs au format standard.** Le service signale les erreurs par des exceptions, traduites en `ProblemDetail` par `ApiExceptionHandler`, qui étend `ResponseEntityExceptionHandler` pour que les erreurs de Spring MVC (paramètre mal typé…) aient le même format. Un statut d'erreur de l'API (saison hors offre, quota dépassé…) est renvoyé tel quel ; son corps est journalisé mais pas transmis. Une panne réseau donne un `502` sans exposer le message de l'exception.
- **Un contrat versionné.** springdoc produit la spécification OpenAPI à partir du contrôleur et des records. `OpenApiContractTest` la réécrit dans `openapi.json` à chaque build et échoue si le fichier versionné était différent : un changement de contrat ne passe pas la CI sans que `openapi.json` soit committé. Le frontend vérifie en CI que ses types sont compatibles avec ce fichier. Tous les champs des réponses y sont obligatoires (Jackson sérialise aussi les `null`) ; ceux qui peuvent valoir `null` portent `@Schema(nullable = true)`.
- **CORS fermé par défaut.** En développement, le frontend passe par le proxy Vite (même origine). En ligne, son domaine doit figurer dans `app.cors.allowed-origins`, et seules les requêtes `GET` sur `/api/**` sont autorisées. Une origine absente de la liste reçoit un `403` sans atteindre le contrôleur, donc sans consommer de quota.
- **Sécurité :**
  - le jeton reste côté serveur ;
  - seuls les en-têtes `Accept` et `Accept-Language` du client sont relayés, et aucun en-tête de la réponse de l'API n'est renvoyé au client ;
  - les paramètres sont validés ;
  - l'URL sortante est vérifiée (même schéma, hôte et port que l'URL configurée) pour empêcher toute requête vers un autre serveur (SSRF).

## Démarrage rapide

### Prérequis

- Java 25 (Maven est fourni par le wrapper `./mvnw`)
- Une clé football-data.org ([inscription gratuite](https://www.football-data.org/client/register))

### Lancer l'application

```bash
git clone https://github.com/foucouthibault/Dashboard-foot-backend.git
cd Dashboard-foot-backend
FOOTBALL_DATA_API_TOKEN=votre_cle ./mvnw spring-boot:run
```

L'API écoute sur http://localhost:8080 :

```bash
curl http://localhost:8080/api/competitions/FL1/standings
```

Sans clé, l'application démarre quand même, mais football-data.org refusera ou limitera les appels.

Pour l'interface, lancer ensuite le [frontend](https://github.com/foucouthibault/Dashboard-foot) : son serveur de développement relaie `/api` vers ce backend.

### Avec Docker

Seul Docker est nécessaire (ni Java ni Maven) :

```bash
docker build -t dashboard-foot-backend .
docker run -p 8080:8080 -e FOOTBALL_DATA_API_TOKEN=votre_cle dashboard-foot-backend
```

- **Deux étapes.** L'image compile le projet sur le JDK 25, puis ne garde que le JRE 25 et le jar (Alpine, environ 360 Mo).
- **Ni clé ni tests dans l'image.** La clé n'est jamais copiée dans l'image : elle est passée au lancement avec `-e`. Les tests ne tournent pas pendant la construction (ils tournent en CI).
- **Utilisateur non root,** port `8080`.
- **Contrôle de santé Docker** (`HEALTHCHECK`) sur `/actuator/health`, qui n'appelle jamais football-data.org.
- **Mémoire :** la JVM limite son tas à 75 % de la mémoire du conteneur (`-XX:MaxRAMPercentage=75`).
- **Configuration :** toute propriété du tableau ci-dessous se passe par variable d'environnement, par exemple `-e APP_CORS_ALLOWED_ORIGINS=https://...`.

## Configuration

Toutes les valeurs sont dans `src/main/resources/application.properties` et peuvent être surchargées par variable d'environnement ou argument `--propriete=valeur`.

| Propriété | Défaut | Rôle |
|---|---|---|
| `football-data.api.token` | variable `FOOTBALL_DATA_API_TOKEN` | Clé d'API football-data.org |
| `football-data.api.base-url` | `https://api.football-data.org/v4` | URL de l'API |
| `football-data.api.rate-limit-per-minute` | `10` | Quota d'appels sortants par minute |
| `football-data.cache.static-ttl-seconds` | `60` | Cache des compétitions et matchs |
| `football-data.cache.current-season-ttl-seconds` | `300` | Cache de la saison en cours |
| `football-data.cache.historical-season-ttl-hours` | `24` | Cache des saisons terminées |
| `app.cors.allowed-origins` | vide (variable `APP_CORS_ALLOWED_ORIGINS`) | Origines autorisées à appeler `/api/**` en `GET` depuis un navigateur, séparées par des virgules (ex. `https://dashboard-foot.example`). Vide : aucune origine externe |
| `management.endpoints.web.exposure.include` | `health` | Endpoints Actuator exposés : seulement la santé, sans détails |
| `springdoc.swagger-ui.enabled` | `true` | Publie Swagger UI sur `/swagger-ui.html` |
| `server.port` | `8080` | Port HTTP |

## Tests

```bash
./mvnw test
```

Les tests ne demandent ni clé d'API ni accès réseau (l'API est simulée) :

- **Contrat OpenAPI** (`OpenApiContractTest`) : `openapi.json` à jour, 4 routes, paramètres, erreurs en `ProblemDetail`, champs obligatoires et `nullable`, Swagger UI servie.
- **Couche HTTP** (`CompetitionProxyHttpTest`) : statut, en-têtes et corps vus par le frontend sur les 4 routes, dont les champs renvoyés et ceux qui sont écartés.
- **Contrôleur** : rejet des identifiants invalides (caractères spéciaux, `/`, injection SQL, remontée de répertoire) et transmission des paramètres.
- **Service** :
  - utilisation du cache, et une clé de cache par saison ;
  - choix du bon cache selon la saison ;
  - erreurs non mises en cache ;
  - transmission des statuts d'erreur ;
  - `429` quand le quota est atteint ;
  - découpage des buteurs.
- **Santé** (`ActuatorHealthTest`) : `/actuator/health` répond `{"status":"UP"}` sans détails ni appel à football-data.org, aucun autre endpoint Actuator n'est exposé.
- **CORS** (`CorsConfigTest`) : en-tête `Access-Control-Allow-Origin` pour une origine autorisée seulement, `GET` uniquement (requête simple et preflight), rien sans configuration.
- **Limiteur de débit** : quota dans la fenêtre, libération des places, calcul de `Retry-After`. Le temps est simulé avec une `Clock` injectée.

La CI GitHub Actions compile le projet et lance les tests à chaque push et chaque pull request. Elle construit aussi l'image Docker sans la publier, puis vérifie que le conteneur démarre et répond `UP` sur `/actuator/health`. Dependabot maintient les dépendances à jour.

## Structure du projet

```
src/main/java/fr/foucouthibault/dashboardfoot/
├── config/
│   ├── AppConfig.java                  # RestTemplate
│   ├── CacheConfig.java                # Les trois caches Caffeine
│   ├── CorsConfig.java                 # Origines autorisées (CORS)
│   └── OpenApiConfig.java              # Description OpenAPI (springdoc)
├── controller/
│   ├── ApiExceptionHandler.java        # Exceptions traduites en réponses HTTP
│   └── CompetitionProxyController.java # Routes /api/competitions et validation
├── exception/                          # Erreurs du service et du contrôleur
├── model/                              # Records des réponses (champs lus par le frontend)
└── service/
    ├── FootballDataProxyService.java   # Appel à l'API, cache, erreurs, sécurité SSRF
    └── FootballDataRateLimiter.java    # Limite de débit à fenêtre glissante
Dockerfile                              # Image en deux étapes (JDK 25 → JRE 25)
openapi.json                            # Contrat de l'API, régénéré par les tests
```

## Limites connues et pistes

- **Cache et quota en mémoire.** Ils sont perdus au redémarrage et propres à chaque instance. Piste : un cache persistant (PostgreSQL ou Redis).
- **Saison terminée ou en cours ?** Le calcul se fait à partir de l'année civile, donc il est approximatif en juillet-août. Au pire, une saison encore active reste en cache un peu plus longtemps que nécessaire.

## Licence

[MIT](./LICENSE)
