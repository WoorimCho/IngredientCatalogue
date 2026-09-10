# IngredientCatalogue

The catalogue of **ingredients** (Food and Ingredient are one unified entity here)
plus the shared **tag** vocabulary and a static **nutrition reference**.

![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-brightgreen)
![Java](https://img.shields.io/badge/Java-17-orange)
![Build](https://img.shields.io/badge/build-Gradle-blue)
![DB](https://img.shields.io/badge/MySQL-8.4-blue)
![Port](https://img.shields.io/badge/port-8082-lightgrey)

> **Shared code** lives in [`../catalogue-common`](../catalogue-common) (Gradle
> composite build): the `Tag` entity + its service/repo, `PageResponse`, the
> RFC-9457 exception advice, the CSV reader, the Zipkin sender. The only
> Ingredient-specific tag code left here is `IngredientTagOwnerCleanup` (clears
> tags off ingredients before a delete/merge).

## Model

- **`Ingredient`** — `id`, `name` (unique-ish), `Set<Tag>`, embedded
  **`Nutrition`** (`basisGrams` default 100 + `kcal, proteinG, carbsG, fatG,
  fiberG, sugarG, sodiumMg`, all nullable), and an optional `densityGPerMl`
  (grams per millilitre — lets the recipe calculators convert a volume amount
  to mass and back).
- **`Tag`** — reusable label, name normalised to lower-case, optional
  `namespace` (free-text grouping only — *not* parsed from a `prefix:` in the
  name) + `description`. Shared by both catalogues (each has its own copy).
- **`NutritionReference`** — common-food figures per `basisGrams`; Flyway-seeded
  (`V3`, 44 rows) and extendable via `POST`.

## API

### `/api/ingredients`
| | |
|---|---|
| `GET /` | `?name=` (substring), `?tag=` (repeatable) + `?match=all\|any`, paged |
| `GET /{id}` | one |
| `GET /by-ids?id=1&id=2` | batch resolve (cross-service; unknown ids omitted) |
| `POST /` | create (`name`, `tags[]`, optional `nutrition`, optional `densityGPerMl`) |
| `PUT /{id}` | replace |
| `PUT /{id}/nutrition` | set/replace (empty body clears) |
| `DELETE /{id}` | delete |
| `POST /{id}/tags`, `DELETE /{id}/tags/{tagName}` | attach / detach |
| `POST /import` (multipart `file`) | CSV bulk import — always 200 with an `ImportResult` |

### `/api/tags`
`GET /` (`?prefix=` — a case-insensitive **substring** match, so "vegan" finds
"diet:vegan"; `?namespace=`), `GET /{id}`, `POST /`, `PUT /{id}`, `DELETE /{id}`,
`POST /merge` (`{from:[ids], into:id}`).

### `/api/nutrition-reference`
`GET /` (`?name=`), **`POST /`** (add a row — 201, or 409 on a duplicate name).

Contract: `src/main/resources/static/openapi.yaml` (served at
`/openapi.yaml`). Paged responses use the stable `PageResponse<T>` envelope.

## Run

```bash
cd .. && docker compose up --build ingredient-catalogue

# local — needs a MySQL with schema `ingredient_catalogue`
docker compose up -d mysql
./gradlew bootRun
```

## Configuration (env)

| Var | Default |
|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://localhost:3307/ingredient_catalogue` |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | `myuser` / `secret` |
| `SERVER_PORT` | `8082` |
| `ZIPKIN_ENDPOINT` | `http://localhost:9411/api/v2/spans` |

Flyway migrations: `V1` schema + tags, `V2` nutrition columns + reference table,
`V3` seed 44 foods, `V4` `density_g_per_ml` column. `ddl-auto=validate`.

## Tests

```bash
./gradlew test    # Testcontainers MySQL: tag search, nutrition round-trip,
                  # reference seed, CSV import, OpenAPI contract, ops endpoints
```

## Security

No Spring Security on the classpath — **every write is unauthenticated**. See
`SECURITY.md` (finding C2). Do not expose `:8082` outside a trusted host.

## Status

**v1 complete** + Phase 4 (nutrition, nutrition-reference incl. `POST`, name
search, CSV import, per-ingredient `densityGPerMl`). Read by the BFF calculators
and written by `recipe-crawler` (creates ingredients it doesn't recognise on
import). `CHANGES.md` in this repo is the running change log for **both
catalogues** (and the cross-cutting BFF / UI / crawler work).
