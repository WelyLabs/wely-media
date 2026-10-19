# wely-media

Service **interne de stockage des images** de la plateforme [Wely Calendar](https://github.com/WelyLabs/wely-platform).

Il possède les octets de chaque image — avatars, photos de conversation, couvertures et justificatifs d'événements, images des communautés — et rien d'autre. Les règles d'accès restent dans les services métier, qui l'appellent en HTTP.

---

## Rôle

| | |
|---|---|
| **Port** | 8085 |
| **Préfixe HTTP** | aucun — les routes sont sous `/internal/media` |
| **Exposition** | aucune route gateway ; Service `ClusterIP`, appelé par `wely-users`, `wely-chat`, `wely-events`, `wely-social` |
| **Stockage** | Garage (API S3), un bucket par service appelant |

---

## Stack

Java 25 · Spring Boot 4 · WebFlux · Spring Security (secret partagé) · ImageIO · MapStruct · springdoc

---

## Pourquoi un service dédié

| | |
|---|---|
| **Les octets ici, les règles là-bas** | Qui peut voir l'avatar d'un utilisateur, la photo d'une conversation ou le justificatif d'une dépense, c'est une question métier : chaque service la tranche avec ses propres données. `wely-media` ne sait rien des utilisateurs ni des conversations ; il normalise, stocke, supprime et signe. |
| **Une seule implémentation** | La normalisation ImageIO, le client S3 et la signature SigV4 existaient en double dans `wely-users` et `wely-chat`. Ils vivent maintenant ici, une fois, avec leurs tests. |
| **Moindre privilège** | Chaque appelant a **son secret**, **son préfixe de clé** et **son bucket**. `wely-chat` ne peut écrire que sous `chat/` dans `wely-chat-media` : un secret volé n'ouvre qu'un seul tiroir. Les identifiants Garage ne sont plus que dans ce service. |
| **Pas de route gateway** | Le navigateur ne parle jamais à `wely-media`. Il reçoit d'un service métier une URL Garage **pré-signée**, à durée limitée, et télécharge directement l'image. |

```
  navigateur ──REST──▶ gateway ──▶ wely-users / wely-chat / wely-events / wely-social
      │                                  │  vérifie les droits
      │                                  │  X-Internal-Secret
      │                                  ▼
      │                            wely-media :8085 ──S3 signé──▶ Garage (interne)
      │                                  │
      │◀──────── URL pré-signée ─────────┘
      │
      └────────── GET pré-signé ──────────────────────────────▶ Garage (hôte public)
```

---

## Architecture

```
                         ┌──────────────────────────────────────────┐
  X-Internal-Secret ────▶│ application/security                     │
                         │   InternalSecretAuthenticationConverter  │
                         │   MediaClientAuthenticationManager       │
                         │ application/rest  MediaController        │
                         └───────────────────┬──────────────────────┘
                                             │ MediaClient (principal)
                         ┌───────────────────▼──────────────────────┐
                         │ domain/                                  │
                         │   services/ MediaService (POJO)          │
                         │   models/   MediaClient · MediaProfile   │
                         │             StoredMedia · NormalizedImage│
                         │   ports/    ObjectStorage · ImageNormalizer
                         └────────▲─────────────────────▲───────────┘
                                  │                     │
            ┌─────────────────────┴──────┐   ┌──────────┴──────────────────┐
            │ infrastructure/storage     │   │ infrastructure/imaging      │
            │ GarageObjectStorageAdapter │   │ ImageIoImageNormalizerAdapter│
            │ GarageRequestSigner (SigV4)│   └─────────────────────────────┘
            │ ListBucketResultParser     │
            └────────────┬───────────────┘
                         ▼
                       Garage
```

`MediaService` vérifie que la clé appartient au préfixe de l'appelant **avant** de décoder quoi que ce soit : une clé interdite ne coûte aucun décodage.

---

## API

Toutes les routes exigent l'en-tête `X-Internal-Secret`.

| Méthode | Route | Corps | Réponse |
|---|---|---|---|
| `POST` | `/internal/media` | multipart : `file` (binaire), `key` (texte), `profile` (texte) | **201** `{ "key", "width", "height", "contentType" }` |
| `DELETE` | `/internal/media?key=…` | — | **204**, y compris si l'objet n'existait plus |
| `DELETE` | `/internal/media/prefix?prefix=…` | — | **204**, even when nothing matched; see [Prefix purge](#prefix-purge) |
| `POST` | `/internal/media/urls` | `{ "keys": [...], "ttlSeconds": 3600 }` | **200** `{ "urls": { "<clé>": "<url pré-signée>" } }` |

### Profils

| Profil | Transformation |
|---|---|
| `AVATAR` | carré centré, 512 × 512 |
| `PHOTO` | côté le plus long ≤ 1600 px, jamais agrandi |
| `COVER` | largeur ≤ 1600 px, proportions gardées, jamais agrandi |
| `RECEIPT` | côté le plus long ≤ 2000 px, jamais agrandi |

Dans tous les cas : JPEG, PNG, GIF ou BMP en entrée ; **JPEG qualité 0,85** en sortie, redessiné sur un fond blanc RVB — les métadonnées EXIF, dont la position GPS, disparaissent. La taille est lue dans l'en-tête **avant** le décodage : au-delà de 8000 px par côté ou de 40 mégapixels, refus sans décoder. ImageIO n'applique pas l'orientation EXIF : l'application web redessine l'image sur un canvas avant l'envoi, ce qui l'applique.

### Clés

Une clé doit commencer par le préfixe de l'appelant, ne contenir que `[A-Za-z0-9/._-]`, ni `..` ni `/` initial, et faire au plus 512 caractères.

### Prefix purge

`DELETE /internal/media/prefix?prefix=avatars/<id>/` deletes **every** object in one of the caller's folders. It serves account deletion: `wely-users` erases `avatars/<businessId>/` with it, including **orphaned old avatars**. Replacing an avatar deletes the old one on a best-effort basis, and no database row references the ones that survived: only walking the folder finds them.

- **Confinement.** The prefix must start with the caller's, end with `/` and name at least one folder below it: `avatars/` alone, `avatars/u1` (which would also cover `avatars/u10/`), `avatars//`, `..` or another service's prefix → **403** `MED-BUS-003`, with nothing listed. Same allowed characters as for a key.
- **Walk.** `ListObjectsV2`, signed like the writes and sent to the internal Garage, **200 keys per page** (a page stays under the HTTP client's 256 KB buffer), following the continuation token. The XML answer is read with StAX, without DTD or external entity. Any document other than a `ListBucketResult` is refused rather than read as an empty listing, and so is a truncated page without a continuation token: the purge then fails with **502** `MED-TEC-001` instead of answering 204 with keys left.
- **Deletion.** Each listed key goes through the same `DeleteObject` as `DELETE /internal/media`, four at a time. A key outside the prefix, whatever the walk returns, is never deleted.
- **Idempotent.** Each call lists what is left: rerunning an interrupted purge, or one that timed out on the caller's side, deletes only the rest and answers **204**.
- The number of deleted objects is logged with the caller's name, **never the prefix**, which contains a user id.

**Retention after deletion.** A purged picture survives up to **7 days** in Garage's nightly backup (`wely-gitops-infra`): its blocks are kept there through hard links, and its key `avatars/<businessId>/…` appears in each day's `db.sqlite`. Restoring Garage within that window must be followed by a new purge of the accounts deleted since the backup day. The same window covers Garage's own block garbage-collection delay.

### URLs signées

- Durée demandée ramenée entre **1 min et 12 h** (1 h si absente).
- **200 clés** au plus par appel ; les doublons sont fusionnés. Une seule clé hors préfixe refuse tout le lot.
- Signées pour l'**hôte public** de Garage — une signature couvre l'hôte — avec `response-cache-control: private, max-age=3600`, pour qu'aucun cache partagé (Cloudflare) ne serve une image privée à quelqu'un d'autre.

### OpenAPI

| | |
|---|---|
| Spec JSON | `http://localhost:8085/v3/api-docs` |
| Swagger UI | `http://localhost:8085/swagger-ui.html` |

Servies sans secret : aucune route gateway n'y mène, le Service est en `ClusterIP`.

---

## Authentification des appelants

Un `AuthenticationWebFilter` Spring Security lit `X-Internal-Secret` et le compare, **en temps constant** (`MessageDigest.isEqual` sur des empreintes SHA-256, pour ne pas révéler non plus la longueur), au secret de **chaque** client configuré, sans sortie anticipée. Le client trouvé devient le principal (`@AuthenticationPrincipal MediaClient`).

- En-tête absent ou inconnu → **401** `MED-REQ-001`.
- Un client dont le secret configuré est vide est **désactivé** : aucun en-tête, vide compris, ne le sélectionne.
- Seuls `/actuator/health/liveness`, `/actuator/health/readiness` et les chemins springdoc sont ouverts sans secret.

---

## Gestion des erreurs

| Code | HTTP | Signification |
|---|---|---|
| `MED-BUS-001` | 400 | Image refusée (format, dimensions, fichier illisible), profil inconnu, partie manquante, plus de 200 clés |
| `MED-BUS-002` | 413 | Fichier de plus de 12 Mo |
| `MED-BUS-003` | 403 | Malformed key or purge prefix, or outside the caller's prefix |
| `MED-REQ-001` | 401 | `X-Internal-Secret` absent ou inconnu |
| `MED-TEC-001` | 502 | Garage injoignable ou en refus |
| `MED-VAL-001` | 400 | Validation du corps JSON, détail par champ |
| `MED-REQ-000` | *repris* | Chemin inconnu, méthode non autorisée, paramètre manquant |
| `MED-TEC-000` | 500 | Erreur inattendue |

Toutes les réponses d'erreur sont des `ProblemDetail` (RFC 7807) :

```json
{
  "type": "https://welylabs.app/problems/med-bus-003",
  "title": "Key not allowed",
  "status": 403,
  "detail": "The key is malformed or outside the prefix granted to this caller.",
  "instance": "/internal/media",
  "code": "MED-BUS-003",
  "timestamp": "2026-10-13T09:12:44.118Z"
}
```

---

## Profils Spring

| Profil | Usage |
|---|---|
| *(aucun)* / `prod` | `application.properties` — tout vient de l'environnement |
| `dev` | Garage local sur `localhost:3900` ; aucun secret par défaut |

---

## Configuration

| Variable | Défaut | Description |
|---|---|---|
| `MEDIA_INTERNAL_ENDPOINT` | — (requis) | Garage interne, pour les écritures (`http://…-garage-service:3900`) |
| `MEDIA_PUBLIC_ENDPOINT` | — (requis) | Hôte public de Garage, pour les URLs signées |
| `MEDIA_REGION` | `garage` | Région S3 déclarée par Garage |
| `MEDIA_ACCESS_KEY` / `MEDIA_SECRET_KEY` | vide | Clé Garage ; vide, le service démarre et répond `MED-TEC-001` |
| `INTERNAL_SECRET_USERS` | vide | Secret de `wely-users` (préfixe `avatars/`) |
| `INTERNAL_SECRET_CHAT` | vide | Secret de `wely-chat` (préfixe `chat/`) |
| `INTERNAL_SECRET_EVENTS` | vide | Secret de `wely-events` (préfixe `events/`) |
| `INTERNAL_SECRET_SOCIAL` | vide | Secret de `wely-social` (préfixe `communities/`) |
| `AVATARS_BUCKET` | `wely-avatars` | Bucket de `wely-users` |
| `CHAT_BUCKET` | `wely-chat-media` | Bucket de `wely-chat` |
| `EVENTS_BUCKET` | `wely-event-media` | Bucket de `wely-events` |
| `COMMUNITIES_BUCKET` | `wely-community-media` | Bucket de `wely-social` |

Un nouvel appelant s'ajoute par trois propriétés `app.clients.<nom>.{secret,prefix,bucket}`. Le préfixe doit se terminer par `/`.

---

## Démarrage

```bash
export INTERNAL_SECRET_CHAT=local-chat-secret
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

```bash
curl -H 'X-Internal-Secret: local-chat-secret' \
     -F file=@photo.jpg -F key=chat/c1/m1.jpg -F profile=PHOTO \
     http://localhost:8085/internal/media
```

Pour lancer **toute la plateforme** sur un Kubernetes local :

```bash
git clone https://github.com/WelyLabs/wely-gitops-infra && cd wely-gitops-infra
kubectl apply -k overlays/local --server-side
```

---

## Tests

```bash
./mvnw test -Dtest='!*IntegrationTest' -DfailIfNoSpecifiedTests=false   # unitaires seuls
./mvnw verify                                                           # + couverture, seuil 80 % de lignes
```

`GarageObjectStorageAdapterIntegrationTest` démarre un vrai Garage (Testcontainers) : il tourne en CI (`CI=true`) ou avec `-Dintegration.tests=true`, et exige un démon Docker.

It covers writes, signed URLs, deletion, and walking a folder over several listing pages without spilling onto a neighbour with a similar name.

> **Note build :** ce service produit dans `target-maven/` et non `target/` en local.

---

## Limites connues

- **Une seule réplique.** Le service est sans état et pourrait monter en charge horizontalement, mais il n'est déployé et éprouvé qu'à `replicas: 1`.
- **L'image entière est en mémoire.** Le fichier reçu, l'image décodée (jusqu'à 160 Mo pour 40 mégapixels) et le JPEG produit coexistent le temps du traitement. Le plafond de 12 Mo par fichier et la limite de pixels bornent ce coût, mais plusieurs envois simultanés s'additionnent.
- **Pas de `NetworkPolicy`.** Tout pod du cluster peut joindre le Service : le secret partagé est la seule barrière. Une politique réseau limitant l'accès à `wely-users`, `wely-chat`, `wely-events` et `wely-social` doublerait cette protection.
- **Pas de rotation de secret à chaud.** Changer un secret demande de redémarrer `wely-media` et l'appelant concerné.
