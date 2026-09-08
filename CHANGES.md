# Catalog microservices — scaffolding change log

**Date:** 2026-08-29
**Scope:** `IngredientCatelog` and `RecipeCatelog` (sibling repos). This file lives
in `IngredientCatelog` but documents both.

Work was done in three passes:

1. **Tag scaffold** — a reusable `Tag` entity, the entity↔tag join table, and a
   tag-search endpoint, built in `IngredientCatelog` as the reference and mirrored
   into `RecipeCatelog`, plus the supporting fixes needed to make each service
   compile and run.
2. **Integration tests** — real HTTP + Testcontainers MySQL coverage of the tag
   flow.
3. **Ops hardening** — Dockerfile + compose app service, Flyway, actuator,
   a checked-in OpenAPI contract, and dependency cleanup.

Everything below was verified: both services pass `./gradlew test` (8 tests each,
Testcontainers MySQL) and both run as a full container stack
(`docker compose --profile full up --build`) with the endpoints exercised by curl.

---

## Update — 2026-08-29 (later): Food and Ingredient unified

The migration decided that **Food and Ingredient are one and the same** — a
single `Ingredient` entry in this catalog represents both. There is no separate
`Food` entity, repository, controller, or `/api/foods` surface, and none is
planned.

**Code impact: none structural.** The `Ingredient` entity already models the
unified concept. What changed:

| File | Change |
|---|---|
| `Model/Ingredient.java` | Class Javadoc: states it covers the monolith's former `Food` + `Ingredient`; any raw-vs-processed style distinction is a tag (e.g. `form:raw`), not a column. |
| `src/main/resources/static/openapi.yaml` | `info.description` says the same. |

**Consequences for the roadmap** (see `../Allergen-Information-System/TODO.txt`):

- The "Absorb Food" tasks (build a `Food` entity + endpoints) are dropped — moot.
- The old domain rule "ingredients can have recipes, food cannot" no longer
  applies; whether a recipe produces/uses an entry stays a Recipe-catalog
  concern (no field here).
- `RecipeCatelog` already references entries by id via `ingredientIds`; that now
  covers everything, so no rename or second id set is needed there.
- With Food folded in, **IngredientCatelog is feature-complete for its v1 scope**
  (entity storage + reusable, editable, searchable custom tags). Remaining work
  is cross-cutting (see the roadmap), not catalog-specific.

The historical record below (the original three-pass scaffold) is unchanged.

---

## Update — 2026-08-29 (later): RecipeCatelog — full recipe model

The scaffold's flat `Recipe` (one TEXT `recipeSteps` blob + a `Set<Long>
ingredientIds`) was replaced with the model from the roadmap.

### New files (RecipeCatelog)

| File | Why |
|---|---|
| `Model/RecipeStep.java` | A step is `{position, text, tools}`. Child `@Entity` with `@OrderColumn` on the parent; `tools` is an `@ElementCollection Set<String>` **per step**. A child entity (not an `@Embeddable` in an `@ElementCollection`) because JPA can't nest an element collection (`tools`) inside an embeddable inside an element collection. |
| `Model/RecipeIngredient.java` | The per-use metadata a flat `Set<Long>` can't hold: `quantity` (free text), `optional`, `replaceable`. `ingredientId` stays a plain `Long` — no cross-service FK. |
| `Model/IngredientReplacement.java` | "If replaceable, list the replacements; if a replacement is makeable, link its recipe." → `ingredientId` (the substitute) + nullable `recipeId` (soft link back into this catalog, no FK). Child of `RecipeIngredient`. |
| `Repositories/RecipeSpecifications.java` | Composable `name` / `ingredient` / `tag` filters for the search endpoint, ANDed. "Has all tags" is **one inner join per tag**, not `group by … having`, so Spring Data's generated count query stays well-behaved. |
| `Dto/RecipeStepDto.java`, `Dto/ReplacementDto.java`, `Dto/RecipeIngredientDto.java`, `Dto/RecipeIngredientResponse.java` | Request/response records for the nested graph. `optional` / `replaceable` are boxed `Boolean` (with `isOptional()` / `isReplaceable()` helpers) — the JSON library under Boot 4 rejects records with **missing primitive** components, so boxing lets the fields be omitted (absent = false). |

### Rewritten (RecipeCatelog)

| File | Change | Why |
|---|---|---|
| `Model/Recipe.java` | `recipeSteps` String → `List<RecipeStep> steps` (`@OrderColumn`). `ingredientIds` `@ElementCollection` → `List<RecipeIngredient> ingredients` (`@OneToMany`, cascade all, orphan removal). Added `creator` (non-null, default `"anonymous"`) and `version` (default 1). Dropped the `name` unique constraint; added unique `(name, creator, version)`. | Roadmap: structured steps with tools; per-ingredient optional/replaceable; "variations … with the same name … (id, name, creator, version)". `creator` is non-null so the composite unique behaves before the User service exists. |
| `Repositories/RecipeRepository.java` | Removed `findByAnyTagName` / `findByAllTagNames` (→ `RecipeSpecifications`). Added `findMaxVersion(name, creator)`. Kept `findByTags_IdIn` (tag delete/merge). | Search moved to specifications; `findMaxVersion` drives auto-versioning. |
| `Dto/RecipeRequest.java`, `Dto/RecipeResponse.java` | Rebuilt around `steps` / `ingredients` (+ `creator`, `version`). `version` boxed → null means "assign the next one". | New shape. |
| `Services/RecipeService.java` + `RecipeServiceImpl.java` | `search(name, tags, match, ingredientIds, pageable)` builds a `Specification` from whichever filters are present. `create` assigns `creator` (`"anonymous"` when null) and `version` (`findMaxVersion + 1` when null). `create`/`update` rebuild the whole child graph. `buildIngredients` rejects `replaceable` with no replacements (`IllegalArgumentException` → 400). | — |
| `Controllers/RecipeController.java` | `GET /api/recipes` gains `?name=` and repeatable `?ingredientId=`, ANDed with the existing `?tag=` / `?match=`. Bodies carry the full graph. | Roadmap: "search recipes by name, tags, food/ingredients". |
| `src/main/resources/db/migration/V1__init.sql` | **Rewritten** (not a V2): `recipe` (+creator/version, new unique), `recipe_step`, `recipe_step_tool`, `recipe_ingredient`, `ingredient_replacement`, and their `*_seq` id tables. | Pre-release, DBs are disposable, and a single coherent V1 beats a V2 that drops half of V1. The file header tells you to `docker compose down -v` if you already applied the old V1 (Flyway checksums migrations). |
| `src/main/resources/static/openapi.yaml` | New `RecipeStep` / `RecipeIngredientInput` / `RecipeIngredientOutput` / `Replacement` schemas; `RecipeRequest`/`RecipeResponse` rebuilt; `name` + `ingredientId` query params; tag-namespace conventions documented. Version bumped to 0.0.2. | Keep the contract accurate. |
| `src/test/java/.../TagSearchIntegrationTest.java` | Bodies updated to the new shape; added tests for full-model round-trip, `replaceable`-without-replacements → 400, auto-versioning, and name / ingredient search. | — |

### "Recipe-exclusive" tags — no new structure

Tools, difficulty, time, category are **ordinary tags**, namespaced by
convention: `tool:beater`, `difficulty:easy`, `time:90min`, `category:snack`.
The existing tag system already does creation, editing, search and merge.
Trade-off: `time:90min` as a string can't be range-filtered or sorted — promote
it to an `Integer` field if "recipes under 30 min" is ever needed.

### Deferred, with reasons

| Item | Why not now |
|---|---|
| Recipes **inherit tags** from their foods/ingredients | Needs a call into the Ingredient catalog. Belongs in the BFF's cross-service composition (roadmap Phase 2), not this service. |
| Search by **favorites** | Favorites are per-user; needs the User service. |
| **N+1** on the list endpoint | Mapping a page touches steps + ingredients + replacements + tags per row. Fine for now; add `@EntityGraph` / fetch joins if pages get large. |
| Step **`tools` order** not preserved on reload | It's a `Set`. Fine for a set of tools; make it a `List` + `@OrderColumn` if order ever matters. |
| **Structured quantity / units** | `quantity` is free text ("2 cups"). Structured amounts are for the Phase 4 portion/nutrition calculators. |

### Verified

`./gradlew test` — **11 tests** (1 `contextLoads` incl. Flyway-then-validate on
the new schema, 4 ops, 6 integration). Full stack in Docker: created a
three-ingredient recipe with per-step tools and a substitute-with-recipe-link,
got `version: 2` on a second POST of the same `(name, creator)`, and searched by
`?name=`, `?ingredientId=`, and `?tag=&match=all`.

---

## Update — 2026-08-29 (later): connecting recipes to ingredients

RecipeCatelog stores `ingredientId` values; IngredientCatelog owns the ingredient
data. The "join" is done at read time in the BFF (the monolith), not in SQL and
not by either catalog calling the other.

### IngredientCatelog — a batch lookup

| File | Change |
|---|---|
| `Controllers/IngredientController.java` | New `GET /api/ingredients/by-ids?id=10&id=11` → `List<IngredientResponse>`. Literal path segment, so it doesn't collide with `GET /api/ingredients/{id}`. |
| `Services/IngredientService(Impl).java` | `getByIds(Collection<Long>)` → `findAllById`, mapped inside `@Transactional(readOnly = true)`. Caps at 500 ids (→ 400). Unknown ids are simply absent from the result. |
| `src/main/resources/static/openapi.yaml` | The new path documented. |
| `src/test/java/.../TagSearchIntegrationTest.java` | `byIdsReturnsOnlyTheIngredientsThatExist`. |

**Why a dedicated endpoint** rather than reusing `GET /api/ingredients?tag=…`:
resolving "these N ids" wants one unpaged list, not a filtered `Page`. One call
per recipe instead of N calls (one per ingredient).

### Allergen-Information-System — the BFF composition layer

New package `com.allergen_info_service.bff` (component-scanned; runs alongside the
existing Thymeleaf app, no change to it):

| File | Role |
|---|---|
| `CatalogClientConfig.java` | Two `RestClient` beans, base URLs from `recipe-catalog.url` / `ingredient-catalog.url` (localhost defaults; overridden with compose/k8s service names later). |
| `RecipeCatalogClient.java` | `getRecipe(id)`. Nested records are this app's view of RecipeCatelog's `RecipeResponse` — only the fields consumed; unknown fields ignored. |
| `IngredientCatalogClient.java` | `byIds(ids)` → `Map<Long, Ingredient>` via the new batch endpoint. |
| `RecipeCompositionService.java` | Fetch recipe → collect every catalog id (line items **and** their substitutes) → one `byIds` call → stitch `name` + `tags` onto each line. Also unions the line ingredients' tags into `inheritedTags`. |
| `ComposedRecipeController.java` | `GET /bff/recipes/{id}` → the composed `RecipeView`. |
| `BffExceptionHandler.java` | Downstream 404 → 404; any other downstream failure → 502. Scoped to the BFF controller so it doesn't touch the app's Thymeleaf error handling. |
| `application.properties` | `recipe-catalog.url` / `ingredient-catalog.url` added. |
| `src/test/java/.../bff/RecipeCompositionServiceTest.java` | `MockRestServiceServer` on both clients — asserts name resolution, `inheritedTags` union, an unresolved id → `{resolved:false, name:"unknown ingredient (#id)"}`, and downstream 404 → `HttpClientErrorException.NotFound`. |

### Design choices

- **The id is the connection.** `RecipeIngredient.ingredientId == Ingredient.id` in the other service — an unenforced foreign key. The join moves from the database to the BFF.
- **Batch, don't N+1.** One `by-ids` call resolves a whole recipe's worth (primaries + replacements).
- **Tolerate dangling ids on read.** An id that no longer resolves becomes `resolved:false` with a placeholder name — no error. No write-time validation that referenced ids exist (yet).
- **Tag inheritance lives here.** "Recipe inherits its ingredients' tags" is `inheritedTags` in the composed view — a read-time union, exactly why it was deferred out of RecipeCatelog.
- **Failures degrade sensibly.** Downstream unreachable / 5xx → `502 Bad Gateway` from the BFF, not a 500.

### Deferred, with reasons

| Item | Why not now |
|---|---|
| **Cache** ingredient lookups in the BFF | Ingredients change rarely; a short-TTL `@Cacheable` on `byIds` is the obvious next step once call volume matters. |
| **Write-time validation** of referenced ids | Costs a round trip and has a TOCTOU gap. Tolerating dangling refs on read is simpler and more resilient to start. |
| **Events** (catalog publishes "ingredient renamed", BFF/Recipe keeps a local read model) | Only worth it if read-time composition becomes a bottleneck. |
| **Compose the Thymeleaf pages** from the catalogs | The BFF endpoint is JSON only; the UI still uses local repos. That switch is the Phase 3 cutover. |

### Verified

`./gradlew test` in IngredientCatelog — **9** (1 `contextLoads`, 4 ops, 4
integration incl. `by-ids`). `mvn test -Dtest=RecipeCompositionServiceTest` in
the monolith — **2**, green. **End to end with all three services in Docker:**
seeded two ingredients in IngredientCatelog and a recipe in RecipeCatelog
referencing them (plus a replacement pointing at a non-existent id), then
`GET localhost:8080/bff/recipes/1` returned the recipe with both line ingredients
resolved to their names + tags, the bad replacement flagged
`"unknown ingredient (#99999)"`, and `inheritedTags`
`["allergen:none","form:processed"]`. `GET /bff/recipes/999` → 404.

---

## Update — 2026-08-29 (later): the User service

New service `Projects/User` (Spring Boot 4.1.1, Gradle) — **port 8084, MySQL
3309, schema `user_service`**. This settles the open question: **User is its own
service, not a BFF module.**

### Domain

| Entity | Fields |
|---|---|
| `Account` | `username`/`email` (unique), `displayName`, `passwordHash` (BCrypt, never serialized), `createdAt`/`updatedAt`, `restrictions` (`Set<String>`), `favoriteRecipeIds` (`Set<Long>`), `favoriteAlternatives` (child list) |
| `FavoriteAlternative` | `{recipeId, ingredientId, replacementIngredientId}`, unique per `(account, recipe, ingredient)` — "for recipe R, prefer substitute S wherever it calls for ingredient I" |
| `Restriction` | seeded advisory catalogue: `code` (unique, e.g. `diet:vegan`), `label`, `kind` (diet / allergen / religious / lifestyle), `description` — ~22 rows in `V2__seed_restrictions.sql` |

All `recipeId` / `ingredientId` values are plain numbers — no FKs across the
service boundary, same as the catalogs.

### Endpoints

- `POST /api/accounts` (register), `GET/PUT /api/accounts/{id}` (profile),
  `PUT /api/accounts/{id}/password` (needs the current one),
  `DELETE /api/accounts/{id}`
- `GET/PUT /api/accounts/{id}/restrictions` (codes normalised to trimmed
  lower-case; free text — the catalogue is advisory, not a constraint)
- `GET /api/accounts/{id}/favorites/recipes`,
  `POST/DELETE .../favorites/recipes/{recipeId}` (a variation is its own recipe id)
- `GET/PUT /api/accounts/{id}/favorites/alternatives`,
  `DELETE .../favorites/alternatives/{altId}`
- `POST /api/authenticate` → `{accountId, username, displayName}` or **401**.
  Verifies credentials only — it does **not** issue a session or token.
- `GET /api/restrictions?kind=…`, `GET /api/restrictions/{code}`

### Design choices

- **Security starter was on the classpath** (Initializr choice), so an explicit
  `SecurityFilterChain` is required or every route gets HTTP Basic with a
  generated password. It's `permitAll` + stateless + CSRF off — the service
  trusts its network. What the config *does* provide is a real
  `PasswordEncoder`, so passwords are **BCrypt hashes, never plaintext**.
- **`authenticate` returns identity, not a token.** Session/token issuance
  belongs to the caller (the BFF), pending the inter-service auth decision.
- **Favourites of "specific alternatives"** (roadmap line 59) = `FavoriteAlternative`:
  the BFF can auto-apply a user's preferred substitution when rendering a recipe.
- Same parity kit as the catalogs: Flyway (`V1` schema + `V2` seed),
  `ddl-auto=validate`, actuator (`health`/`info`/`prometheus`), `buildInfo()`,
  static `openapi.yaml`, Dockerfile + profile-gated compose `app`,
  Testcontainers. Same Boot-4 gotchas already documented (sliced test starters,
  `spring-boot-flyway` module for the Flyway autoconfig).

### Deferred, with reasons

| Item | Why not now |
|---|---|
| **Session / token issuance** and **who may read/modify account N** | The inter-service auth mechanism is still an open decision. `authenticate` is the primitive; enforcement is the BFF's job once that's decided. |
| **"Search my favourite recipes"** | The User service holds the favourite recipe ids; turning that into recipe results is a BFF call to RecipeCatelog (`GET /api/recipes?…` filtered by those ids). |
| **Rate limiting** on register / authenticate | A gateway / filter concern; not while everything is on a trusted network. |
| **Email verification, password reset** | Real account lifecycle; out of scope for v1. |

### Verified

`./gradlew test` — **12** (1 `contextLoads` incl. Flyway `V1`+`V2` then
validate, 5 ops incl. the restriction seed, 6 account-flow: register →
authenticate by username and email → wrong password / unknown → 401, duplicate
username → 400, change-password invalidates the old one, restriction
normalisation + clear, favourite recipes add/remove, favourite alternatives
replace/delete, catalogue `?kind=` filter). **Live in Docker:** registered an
account, authenticated (good → 200, bad → 401), set `" Diet:Vegan "` → stored as
`diet:vegan`, favourited recipe 7 and a substitution, read the seeded
catalogue.

---

## Update — 2026-08-29 (later): top-level compose (the whole system)

Two new files at the `Projects/` root turn the four services + GoodNight into one
runnable system:

| File | Role |
|---|---|
| `Projects/compose.yaml` | `docker compose up --build` → `mysql` + `ingredient-catalog` + `recipe-catalog` + `user` + `goodnight` + `bff`, one network, service-name DNS. |
| `Projects/infra/mysql-init/01-databases.sql` | Runs once on first MySQL init: creates `mydatabase` / `ingredient_catalog` / `recipe_catalog` / `user_service` and grants `myuser` on each. |

### Decisions this settles

- **DB topology: one MySQL instance, one schema per service.** Simplest thing
  that works on a dev box; the init script owns schema creation, each service's
  `SPRING_DATASOURCE_URL` points at `mysql:3306/<its_schema>`.

### Wiring

- BFF env: `INGREDIENT_CATALOG_URL=http://ingredient-catalog:8082`,
  `RECIPE_CATALOG_URL=http://recipe-catalog:8083`,
  `GOODNIGHT_URL=http://goodnight:8085` — the localhost defaults in
  `application.properties` are overridden with compose service names.
- GoodNight's feign callback → `http://bff:8080/response`.
- **Health-gated startup:** everything waits for `mysql` healthy; the `bff` waits
  for all three catalogs *healthy* (not just started), so `GET /bff/recipes/{id}`
  works the instant `up` returns. Healthchecks: `mysqladmin ping` (mysql),
  `curl -fsS /actuator/health` (catalogs + user — the `eclipse-temurin:17-jre`
  image ships curl), busybox `wget --spider /` (bff — the monolith image is
  alpine and has no actuator), and GoodNight's own baked `HEALTHCHECK`.
- All ports still published (8080/8082/8083/8084/8085, 3306) for direct curl.
- The per-service `compose.yaml` files (with the `--profile full` app service)
  stay as-is for isolated single-service dev.

### Verified

`docker compose up -d` from `Projects/` → all six containers report **healthy**
(bff last, ~40s after the catalogs). Then: seeded an ingredient in
`ingredient-catalog` and a recipe in `recipe-catalog` referencing it;
`GET localhost:8080/bff/recipes/1` returned the composed view with the
ingredient resolved to `rice noodles` + `inheritedTags:["allergen:none"]` —
i.e. `bff → recipe-catalog → ingredient-catalog` all over the compose network by
service name. Registered a user, read the seeded restriction catalogue. One
MySQL, four schemas (`show databases` confirmed). Torn down with `down -v`.

First `up --build` is slow — five images, and the monolith + GoodNight build
with plain Maven (no layer cache). Rebuilds are fast.

---

## Update — 2026-08-29 (later): BFF ↔ User composition

The BFF now personalises a recipe for an account, and lists an account's
favourites.

### RecipeCatelog — batch lookup

Mirror of IngredientCatelog's: `GET /api/recipes/by-ids?id=1&id=2` →
`List<RecipeResponse>` (unknown ids omitted, 500 cap). `RecipeService.getByIds`
via `findAllById`. + openapi + one integration test. **12 tests** now.

### Allergen-Information-System — new BFF pieces

| File | Change |
|---|---|
| `bff/UserServiceClient.java` (new) | `restrictions(id)`, `favoriteRecipeIds(id)`, `favoriteAlternatives(id)` against the User service. |
| `bff/CatalogClientConfig.java` | Third `RestClient` bean; `user-service.url` (localhost default). |
| `bff/RecipeCatalogClient.java` | `getRecipesByIds(ids)` for the favourites list. |
| `bff/RecipeCompositionService.java` | `compose(id)` → `compose(id, accountId)`. With an account it also fetches restrictions + favourite substitutions and annotates: `restrictionConflicts` (line ingredients whose tags match a code the account avoids), `compatibleWithAccount`, and per line `preferredReplacementId` / `preferredReplacementName`. New `favoriteRecipeSummaries(accountId)`. New records `RestrictionConflict`, `RecipeSummary`; `RecipeView` / `Line` gained the fields above. |
| `bff/ComposedRecipeController.java` | `@RequestMapping` widened to `/bff`. `GET /bff/recipes/{id}?accountId=` and `GET /bff/accounts/{accountId}/favorite-recipes`. |
| `application.properties`, `Projects/compose.yaml` | `user-service.url` / `USER_SERVICE_URL=http://user:8084`. |
| `bff/RecipeCompositionServiceTest.java` | Third mock server; tests for the personalised compose and the favourites list. **4 tests**. |

### Design notes

- **A conflict is a literal tag match** — the account avoids `allergen:peanut`
  and some line ingredient carries the tag `allergen:peanut`. Clean for
  `allergen:*`; cruder for `diet:*` (there's no "this recipe *is* vegan"
  inference). Good enough for v1; document the limit.
- **The BFF annotates, it doesn't rewrite.** `preferredReplacementId` on a line
  tells the UI "this account would swap in X" — RecipeCatelog is untouched.
- **`null` vs `[]`** — `restrictionConflicts` / `compatibleWithAccount` are
  `null` when no `accountId` was given ("not evaluated"), a (possibly empty)
  value when it was.
- Downstream calls are still **sequential** (recipe → restrictions → favourite
  alternatives → by-ids). Fine at this size; parallelise if it matters.

### Verified

`RecipeCompositionServiceTest` 4/4 (`MockRestServiceServer` on all three
clients). **Live, all four services in the top-level compose:** an account with
an `allergen:peanut` restriction, a recipe using a `allergen:peanut`-tagged
ingredient with a peanut-free substitute, and a favourite-alternative preferring
that substitute →
`GET /bff/recipes/1?accountId=1` returned
`compatibleWithAccount:false`,
`restrictionConflicts:[{restriction:"allergen:peanut",ingredientIds:[1],ingredientNames:["tamarind paste"]}]`,
and `preferredReplacementName:"peanut-free tamarind"` on that line.
`GET /bff/accounts/1/favorite-recipes` → the recipe summary.

---

## Update — 2026-08-29 (later): Phase 1 finished — GoodNight cleanup + BFF-session auth

### GoodNight client

`GoodNightRestClientImpl` (monolith) — `new RestTemplate()` per call → one
injected `RestClient` (Boot's auto-configured `RestClient.Builder`, base URL from
`goodnight.url`). Errors narrowed from `catch (Exception)` to
`catch (RestClientException)`; logger name corrected. That's the last raw
`RestTemplate` in the codebase. + `GoodNightRestClientImplTest`
(`MockRestServiceServer`, 2). Verified live: `GET /request` → posts to
`goodnight:8085/snack` and returns the body.

### Maven wrapper

`Allergen-Information-System/.mvn/wrapper/maven-wrapper.properties` was missing,
so `./mvnw` failed and there was no system `mvn`. Restored (script-only wrapper,
`distributionUrl` → Maven 3.9.9). `./mvnw` works again.

### Auth — decision: **BFF-enforced session** (chosen over per-service OAuth2)

For one BFF + a Thymeleaf UI on a trusted network, a session cookie is
proportionate; OAuth2 resource servers everywhere would be plumbing for its own
sake. The services stay identity-agnostic; the BFF is the enforcement point.

| File | Role |
|---|---|
| `pom.xml` | `spring-boot-starter-security` (+ `spring-security-test`). Also removed a duplicate `spring-boot-starter-web` entry. |
| `bff/BffSecurityConfig.java` | **Two filter chains.** `@Order(1)` matches `/bff/**` → `authenticated()`, form login at `POST /bff/login` (204 / 401), `POST /bff/logout` (204), CSRF off (JSON surface). `@Order(2)` matches everything else → `permitAll` — **the legacy Thymeleaf UI is untouched** (the monolith stays intact until the Phase 3 cutover). |
| `bff/UserAuthenticationProvider.java` | Login delegates to the User service (`POST /api/authenticate`); on success the session principal is a `BffPrincipal(accountId, username, displayName)`. Bad creds → `BadCredentialsException` → 401. |
| `bff/BffPrincipal.java` | `Serializable` record in the session. |
| `bff/UserServiceClient.java` | New `authenticate(identifier, password)` → `Identity`. |
| `bff/ComposedRecipeController.java` | Account id now comes from `@AuthenticationPrincipal BffPrincipal` — **the `?accountId=` param is gone**. `/bff/accounts/{id}/favorite-recipes` checks the id matches the caller → else `AccessDeniedException`. |
| `bff/BffExceptionHandler.java` | `AccessDeniedException` → 403 `ProblemDetail`. |
| `bff/ComposedRecipeControllerSecurityTest.java` | `@WebMvcTest` (no MySQL, dodges the Testcontainers OOM): no session → 401; with a session → composes for that account; other account's favourites → 403. 3 tests. |

**What's still open (deliberately):** direct calls to the catalog / User
services are unauthenticated — they trust the network. The BFF does not yet
forward the identity as a header for the services to verify themselves; that's
the hardening step if/when this moves toward the "middle path" (a signed token
on BFF→service calls). `BffSecurityConfig` is the swap point.

### Verified

Non-Testcontainers monolith tests: **22** (`ComposedRecipeControllerSecurityTest`
3, `RecipeCompositionServiceTest` 4, `GoodNightRestClientImplTest` 2,
`BasicServiceImplTest` 13). **Live, full compose stack:**
`GET /bff/recipes/1` no session → 401; `POST /bff/login` wrong password → 401,
right → 204 + session cookie; `GET /bff/recipes/1` with the cookie → personalised
(`restrictionConflicts`, `compatibleWithAccount:false`) with the account id taken
from the session, no query param; `/bff/accounts/1/favorite-recipes` → 200,
`/bff/accounts/999/...` → 403; `GET /` (legacy UI) → 200, still open.

### Phase 1 is complete

All four services (IngredientCatelog, RecipeCatelog, User, GoodNight) are
v1-done, tested, and Docker-verified; the BFF composes them, personalises for an
account, and enforces a session. What remains under the Phase 1 headings is
Phase 2 (tracing, metrics, CI, contract tests), the Phase 3 cutover (gut the
monolith / point the Thymeleaf UI at the services), or "Later" polish.

---

## Update — 2026-08-30: Phase 2 — observability, quick wins, CI

### Metrics — Prometheus + Grafana  (done, verified)

| File | Role |
|---|---|
| `Projects/compose.yaml` | New `prometheus` (`:9412` — 9090/9091 are in a Windows reserved port range here), `grafana` (`:3000`, anonymous admin), `zipkin` (`:9411`) services. |
| `Projects/infra/prometheus/prometheus.yml` | Scrapes `/actuator/prometheus` on `bff` / `ingredient-catalog` / `recipe-catalog` / `user` (GoodNight has no actuator). A `relabel` sets a `service` label from the target host. |
| `Projects/infra/grafana/provisioning/…` | Prometheus + Zipkin datasources; a file provider for dashboards. |
| `Projects/infra/grafana/dashboards/system-overview.json` | 5 panels: request rate, p95 latency, 5xx rate, JVM heap, Hikari connections — all `by (service)`. |
| Monolith `pom.xml` | Added `spring-boot-starter-actuator` + `micrometer-registry-prometheus` (it had neither). |

**Verified live:** `docker compose up` → all 9 containers healthy;
`http://localhost:9412/api/v1/targets` shows all four Spring targets **UP**;
Grafana `/api/health` ok with both datasources provisioned and the dashboard
loaded.

### Tracing — Micrometer Tracing → Zipkin  (partial)

Deps + config added to all four services:
`micrometer-tracing-bridge-brave` + a Zipkin reporter, `management.tracing.*` /
`management.zipkin.*`, `SPRING_APPLICATION_NAME` + a `[service,traceId,spanId]`
log pattern per service in compose.

**Boot 4 split the tracing autoconfiguration into three modules** (Boot 3 had it
all in `spring-boot-actuator-autoconfigure`):
`spring-boot-micrometer-tracing` (the Micrometer bridge),
`spring-boot-micrometer-tracing-brave` (`BraveAutoConfiguration`),
`spring-boot-zipkin` (`ZipkinAutoConfiguration`). All three are now on the three
Gradle services.

**Root cause found & fixed (partial):** `spring-boot-micrometer-tracing-brave`
provides `BraveAutoConfiguration` but does **not** bundle the actual bridge
library `io.micrometer:micrometer-tracing-bridge-brave` (which brings
`io.zipkin.brave:brave`). When I moved from the raw libs to the Boot modules I
dropped that dep, so `BraveAutoConfiguration`'s
`@ConditionalOnClass({brave.Tracer, BraveTracer})` failed → it backed off → the
`NoopTracer` took over (empty `[service,,]` MDC). **Re-added
`io.micrometer:micrometer-tracing-bridge-brave` to the three Gradle builds** —
`brave:6.3.1` is now on the classpath and **the tracer engages** (real
`[recipe-catalog,<traceId>,<spanId>]` appears in logs, no longer `,,`).

**Still open — span export.** The Boot 4.1 services' spans do not reach Zipkin.
`spring-boot-zipkin:4.1.0`'s `ZipkinHttpClientSender` (uses
`java.net.http.HttpClient`) throws `java.net.ConnectException` posting to
`http://zipkin:9411/api/v2/spans`, even though `curl` to that exact URL from the
same container returns `202`. Added `-Djava.net.preferIPv4Stack=true` via
`JAVA_TOOL_OPTIONS` — didn't resolve it. Looks like a `spring-boot-zipkin`
4.1.0 (.0 release) issue or a JDK-HttpClient/Docker-DNS interaction. Options for
the follow-up: pin/await 4.1.1+, or switch these services to the OTLP exporter
(→ an OTel collector / Tempo) instead of the Zipkin HTTP sender. The BFF (Boot
3.5, `RestTemplate`-based Zipkin sender) exports fine.

### Tests — repository slice per service + full-suite pass  (done)

**`@DataJpaTest` per service.** A repository-layer test that runs against the
**real MySQL** (Testcontainers, `@ServiceConnection`), so the hand-written,
dialect-sensitive queries are genuinely exercised rather than silently
reinterpreted by H2:

| Service | New test (cases) | Exercises |
|---|---|---|
| IngredientCatelog | `IngredientRepositoryDataJpaTest` (3) | `findByAllTagNames` (`group by … having count(distinct t.id)`), `findByAnyTagName`, `findByTags_IdIn` |
| RecipeCatelog | `RecipeRepositoryDataJpaTest` (3) | `findMaxVersion` (`coalesce(max(version), 0)`), `findByTags_IdIn`, the `hasAllTags` / `hasAnyTag` Specifications (one inner join per tag) |
| User | `AccountRepositoryDataJpaTest` (2) | case-insensitive account lookups; the `restriction` catalogue that migration `V2` seeds |

Each is `@DataJpaTest(properties = { ddl-auto=validate, flyway.enabled=true })` +
`@AutoConfigureTestDatabase(replace = NONE)` +
`@Import(TestcontainersConfiguration)` — Flyway builds the schema, Hibernate
validates against it, same as production.

Boot 4.1 moved the slice annotations out of `spring-boot-test-autoconfigure`
into per-module packages behind a new starter,
`org.springframework.boot:spring-boot-starter-data-jpa-test` (added
`testImplementation` on the three Gradle services): `@DataJpaTest` →
`org.springframework.boot.data.jpa.test.autoconfigure`,
`@AutoConfigureTestDatabase` → `org.springframework.boot.jdbc.test.autoconfigure`.

**MySQL test image pinned to `8.4` everywhere.** The monolith's
`TestcontainersConfiguration` (and its dev `compose.yaml`, and RecipeCatelog's
test config) were still on `mysql:latest` = now 9.x. The monolith's older
Testcontainers injects a legacy `innodb_log_file_size` tuning that MySQL 9
removed, so the container refused to boot and
`AllergenInformationSystemTests.contextLoads` failed. All four services + both
compose layers now say `mysql:8.4` (the LTS the top-level `compose.yaml` already
used).

**Full suites — all green:**

| Service | Tests | Command |
|---|---|---|
| IngredientCatelog | 12 | `./gradlew test` |
| RecipeCatelog | 15 | `./gradlew test` |
| User | 14 | `./gradlew test` |
| Allergen-Information-System (monolith + BFF) | 24 | `./mvnw test` — surefire `forkCount=1`, no OOM, ~6 min |

### Quick wins  (done)

- **BFF 502-path test** — `ComposedRecipeControllerSecurityTest` now asserts a
  downstream `RestClientException` → 502. 4 tests.
- **`mvn test` OOM** — `maven-surefire-plugin` config: `forkCount=1`,
  `reuseForks=true`, `-Xmx1024m -XX:+UseSerialGC` (default is one fork per core,
  which blew memory alongside Testcontainers + the build JVM).
- **Dead build wiring removed** — the `asciidoctor` plugin + `spring-restdocs`
  test deps + `snippetsDir` + the `tasks.test`/`tasks.asciidoctor` blocks from
  the two catalog `build.gradle.kts`; **7 duplicate `tools.jackson` deps** from
  the monolith `pom.xml`.
- **Dockerfile cache ids** — each catalog's Gradle build cache mount got a
  distinct `id=`, so `docker compose build` (parallel) no longer deadlocks on a
  shared `/root/.gradle/caches/journal-1` lock.

### CI  (files written; can't run here)

The services are in separate git repos, so it's one workflow per repo, not one
pipeline:
- `Allergen-Information-System/.github/workflows/ci.yml` — `mvnw test` on
  push/PR; build + push `ghcr.io/<repo>:<sha>` on push to `main`.
- `Projects/infra/ci/gradle-service-ci.yml` — the Gradle equivalent, to copy
  into each catalog / User repo.
- `Projects/infra/ci/README.md` — the per-repo setup + the "monorepo or
  submodules" prerequisite for a single pipeline.

### Deferred (documented in TODO.txt)

Contract tests (BFF ↔ catalogs) and the `Page` → stable-envelope change.

---

## Cross-cutting rationale

These principles drove most of the individual edits, so the per-file notes stay short.

| Principle | Consequence in the code |
|---|---|
| **Each service owns its schema.** | Separate DB per service (`ingredient_catalog`, `recipe_catalog`); no shared tables, no cross-service foreign keys. |
| **Cross-service references are by id, not JPA relationships.** | `Recipe` holds `Set<Long> ingredientIds` (an `@ElementCollection`), not a `@ManyToMany Ingredient`. `Ingredient.hasRecipe` was deleted — "does a recipe use this?" is the Recipe service's question. |
| **Tags are reusable and searchable.** | `Tag` is its own entity with a unique, normalised (trimmed, lower-cased) name; many-to-many to the catalog entity; get-or-create on write; search endpoints with `match=all` / `match=any`; prefix autocomplete; merge. |
| **These are JSON APIs, not server-rendered views.** | `@Controller` → `@RestController`; methods return DTOs, never entities (lazy `tags` + `spring.jpa.open-in-view=false` would otherwise throw during serialization). DTO mapping happens inside `@Transactional(readOnly = true)` service methods. |
| **Flyway owns the schema; Hibernate only validates it.** | `spring.jpa.hibernate.ddl-auto=validate`; `V1__init.sql` per service. |
| **Drop unused starters/plugins.** | Removed `webflux`, `webservices` (SOAP), the `postgresql` driver, and the `graalvm-native` plugin. |

### Spring Boot 4.1 quirks hit along the way

- **Test starters are sliced.** `@AutoConfigureMockMvc` moved to
  `org.springframework.boot.webmvc.test.autoconfigure`; `TestRestTemplate` moved to
  `org.springframework.boot.resttestclient` and is no longer pulled by default.
  The tests use `MockMvc` from `spring-boot-starter-webmvc-test`.
- **Flyway autoconfiguration lives in `org.springframework.boot:spring-boot-flyway`**,
  not in `flyway-core` alone. Adding only `flyway-core` silently does nothing
  (migrations don't run, then `validate` fails with "missing table").
- **springdoc-openapi has no Spring Boot 4 build.** 2.8.x references
  `org.springframework.data.util.TypeInformation`, removed in Spring Data 4 — it
  fails during AOT introspection. Replaced with a hand-written, checked-in
  `openapi.yaml`.
- **The `graalvm-native` plugin forces an AOT build step** during `test`/`bootJar`;
  removing the (unused) plugin both drops that overhead and side-stepped the
  springdoc AOT failure.

---

## IngredientCatelog

### New files

| File | Why |
|---|---|
| `Model/Tag.java` | Reusable label entity: `id`, `name` (unique, normalised), `namespace`, `description`. No `Set<Ingredient>` back-reference so touching a popular tag doesn't load every ingredient. Id-based `equals`/`hashCode`. |
| `Repositories/TagRepository.java` | `findByName`, `findByNameIn` (get-or-create), `findByNameStartingWithIgnoreCase` (autocomplete), `findByNamespaceIgnoreCase`. |
| `Dto/TagRequest.java`, `Dto/TagResponse.java`, `Dto/TagNamesRequest.java`, `Dto/MergeRequest.java`, `Dto/IngredientRequest.java`, `Dto/IngredientResponse.java` | Request/response `record`s. Keep JPA entities off the wire; carry Bean Validation constraints (`@NotBlank`, `@Size`, `@NotEmpty`). |
| `Exception/NotFoundException.java` | Signals a 404 from the service layer. |
| `Controllers/ApiExceptionHandler.java` | `@RestControllerAdvice`: `NotFoundException`→404, `IllegalArgumentException`→400, `DataIntegrityViolationException`→409, `MethodArgumentNotValidException`→400 with field errors — all as RFC 9457 `ProblemDetail`. |
| `Services/TagService.java` + `TagServiceImpl.java` | Tag CRUD; `resolve()` get-or-create by normalised name; `delete()` detaches from every ingredient first; `merge()` repoints links then deletes the source tags. `normalise()` is the single canonicalisation point. |
| `Services/IngredientServiceImpl.java` | Replaces `IngredientServiceImplementation.java` (renamed for consistency with `TagServiceImpl`). Real CRUD + `search(tags, match, pageable)` + attach/detach tags. All `@Transactional`; DTO mapping done inside the transaction. |
| `Controllers/TagController.java` | `/api/tags` CRUD + `?prefix=` / `?namespace=` listing + `POST /api/tags/merge`. |
| `src/main/resources/db/migration/V1__init.sql` | Flyway baseline. Generated from Hibernate's own schema export, then constraint names made stable/readable. Creates `ingredient`, `tag`, `ingredient_tag`, and the `*_seq` id tables. |
| `src/main/resources/static/openapi.yaml` | OpenAPI 3.1 contract for every endpoint + schema, served statically at `/openapi.yaml`. Replaces springdoc (no Boot 4 build). |
| `Dockerfile` | Multi-stage: `eclipse-temurin:17-jdk` builds the boot jar with a BuildKit Gradle-cache mount; `eclipse-temurin:17-jre` runs it as a non-root user. `EXPOSE 8082`. |
| `.dockerignore` | Excludes `build/`, `.gradle/`, `.idea/`, `.git/`, docs from the build context. |
| `src/test/java/.../TagSearchIntegrationTest.java` | `@SpringBootTest` + `MockMvc` + Testcontainers MySQL. Covers tag normalisation/get-or-create, `match=all` (the grouped `having count` query **and its paginated total**), `match=any`, empty result, and merge. |
| `src/test/java/.../OpsEndpointsTest.java` | `/actuator/health` UP, `/actuator/info` build version, `/actuator/prometheus`, `/openapi.yaml`. Also proves Flyway-then-`validate` succeeds on boot. |

### Modified files

| File | Change | Why |
|---|---|---|
| `Model/Ingredient.java` | Removed `hasRecipe`. Replaced the broken `@ManyToMany(mappedBy = "Tagged")` with an owning-side `@JoinTable(name = "ingredient_tag", …)`. Added `addTag`/`removeTag`/`removeTagsById`/`replaceTags`. Id-based `equals`/`hashCode`. `protected` no-arg ctor + `Ingredient(String name)`. | `hasRecipe` had a duplicate `@Column(name = "name")` bug and is cross-service state. The old many-to-many was mismapped on both sides (a single `Ingredient` field annotated `@ManyToMany`). |
| `Repositories/IngredientRepository.java` | `CrudRepository` → `JpaRepository` + `JpaSpecificationExecutor`. Added `findByAnyTagName`, `findByAllTagNames` (JPQL `group by … having count(distinct t.id) = :count`, with an explicit `countQuery`), `findByTags_IdIn`. | Pagination, sorting, and a spec extension point; the two tag searches are hand-written so their count behaviour is predictable. |
| `Services/IngredientService.java` | Interface rewritten around DTOs and `search(...)`. | Matches the new controller/service contract. |
| `Controllers/IngredientController.java` | `@Controller` → `@RestController`. Returns DTOs, not view-name strings. Added `?tag=&match=` search params, `POST/DELETE /{id}/tags`, `@Valid @RequestBody`, `@PageableDefault(sort = "id")`. | It was returning bare strings that Spring resolved as Thymeleaf view names. `sort=id` because the `match=all` query groups by id and some databases reject ordering by a non-grouped column. |
| `src/main/resources/application.properties` | Added: MySQL datasource (`localhost:3307`, schema `ingredient_catalog`, `createDatabaseIfNotExist`), `server.port=8082`, `spring.jpa.open-in-view=false`, `ddl-auto` `update`→`validate`, Flyway settings, `spring.data.rest.detection-strategy=annotated` + `base-path=/data`, actuator exposure (`health,info,prometheus`) + probe groups. | The file previously held only `spring.application.name`, so the app could not start (no datasource). Data REST is neutered so the hand-written controllers are the only surface. |
| `build.gradle.kts` | See the shared dependency table below. Also added `springBoot { buildInfo() }` and removed the `org.graalvm.buildtools.native` plugin. | `buildInfo()` populates `/actuator/info`. The native plugin was unused and forced an AOT step. |
| `compose.yaml` | Removed the Postgres service. Pinned `mysql:8.4`. Host port `3307:3306`. Added a `mysqladmin ping` healthcheck. Added an `app` service (`build: .`, env-var datasource, `8082:8082`, `depends_on: mysql: condition: service_healthy`) **gated behind `profiles: ["full"]`**. | Standardised on MySQL. The profile gate means `bootRun` / Spring's dev docker-compose integration still start only MySQL; the whole stack runs with `docker compose --profile full up`. |
| `src/test/java/.../TestcontainersConfiguration.java` | Removed the Postgres `@ServiceConnection` container. | Two `@ServiceConnection` JDBC containers make the datasource ambiguous and the context fails to start. |

### Deleted files

| File | Why / replacement |
|---|---|
| `Model/IngredientTags.java` | Mismapped many-to-many; tag was bound to exactly one ingredient, defeating reuse. → `Model/Tag.java`. |
| `Repositories/IngredientTagRepository.java` | → `Repositories/TagRepository.java`. |
| `Services/IngredientTagService.java` | Interface with no implementation and no controller. → `Services/TagService.java` + `TagServiceImpl.java`. |
| `Services/IngredientServiceImplementation.java` | Renamed to `IngredientServiceImpl.java` and rewritten. |

---

## RecipeCatelog

Started from a near-empty skeleton (only `Recipe`, `RecipeRepository`,
`RecipeCatelogApplication`, one test). It received the **same** `Tag` /
`TagRepository` / `Dto` / `Exception` / `ApiExceptionHandler` / `TagService(+Impl)`
as `IngredientCatelog` (identical except package), plus:

### New files (Recipe-specific)

| File | Why |
|---|---|
| `Services/RecipeService.java` + `RecipeServiceImpl.java` | Mirror of `IngredientService`; `create`/`update` also persist `recipeSteps` and `ingredientIds`. |
| `Controllers/RecipeController.java` | `/api/recipes` — mirror of `IngredientController`. |
| `src/main/resources/db/migration/V1__init.sql` | Creates `recipe`, `tag`, `recipe_tag`, `recipe_ingredient_ids` (`@ElementCollection`, FK on `recipe_id` only — `ingredient_id` intentionally has no FK), `*_seq`. |
| `src/main/resources/static/openapi.yaml` | Recipe-flavoured contract (`recipeSteps`, `ingredientIds`). |
| `Dockerfile`, `.dockerignore` | Same as IngredientCatelog; `EXPOSE 8083`. |
| `src/test/java/.../TestcontainersConfiguration.java` | New — mirrors IngredientCatelog (MySQL `@ServiceConnection`). Did not exist before. |
| `src/test/java/.../TestRecipeCatelogApplication.java` | New — dev entrypoint that boots with the Testcontainers config, for parity with IngredientCatelog. |
| `src/test/java/.../TagSearchIntegrationTest.java`, `OpsEndpointsTest.java` | Mirrors; the recipe search test also asserts `ingredientIds` + `recipeSteps` round-trip. |

### Modified files

| File | Change | Why |
|---|---|---|
| `Model/Recipe.java` | Was: referenced a nonexistent `Ingredient` class and put `@ManyToMany` on a single field — did not compile. Now: `id`, `name` (unique), `recipeSteps` (`@Column(length = 20_000)` → `TEXT`), `ingredientIds` (`@ElementCollection Set<Long>`, no cross-service FK), `tags` (owning-side `@JoinTable(name = "recipe_tag", …)`), plus helper methods. | Make it compile; model ingredient references the microservice way (by id). |
| `Repositories/RecipeRepository.java` | `CrudRepository` → `JpaRepository` + `JpaSpecificationExecutor`; added `findByAnyTagName`, `findByAllTagNames`, `findByTags_IdIn`. | Same as IngredientRepository. |
| `src/main/resources/application.properties` | Was just `spring.application.name`. Now full config: MySQL datasource (`localhost:3308`, schema `recipe_catalog`), `server.port=8083`, Flyway, `ddl-auto=validate`, `open-in-view=false`, Data REST neutered, actuator. | App could not start before. |
| `build.gradle.kts` | Same dependency/plugin changes as IngredientCatelog (shared table below). Also **fixed a copy-paste bug**: `tasks.generateJava { packageName = "com.example.ingredientcatelog.codegen" }` → `com.example.recipecatelog.codegen`. Removed the redundant explicit `jakarta.persistence:jakarta.persistence-api` (comes with `starter-data-jpa`). Added `springBoot { buildInfo() }`. | Correctness + consistency with IngredientCatelog. |
| `compose.yaml` | Was infra-only (`mysql:latest`, ephemeral port). Now `mysql:8.4`, host port `3308:3306`, healthcheck, and a profile-gated `app` service (`8083:8083`, schema `recipe_catalog`). | Same rationale as IngredientCatelog. |
| `src/test/java/.../RecipeCatelogApplicationTests.java` | Added `@Import(TestcontainersConfiguration.class)`. | The context now needs a datasource (JPA entities + Flyway). |

---

## Shared `build.gradle.kts` dependency delta (both services)

| Action | Coordinate | Why |
|---|---|---|
| **add** | `org.springframework.boot:spring-boot-starter-data-jpa` | JPA autoconfig was genuinely missing — the app could not wire an `EntityManagerFactory`. (This is why RecipeCatelog had a stray hand-added `jakarta.persistence-api`.) |
| **add** | `org.springframework.boot:spring-boot-starter-validation` | Make `@NotBlank`/`@Size`/`@Valid` actually enforced at runtime. |
| **add** | `org.springframework.boot:spring-boot-starter-actuator` | `/actuator/health`, `/info`, `/prometheus`. |
| **add** | `org.springframework.boot:spring-boot-flyway` | Carries `FlywayAutoConfiguration` in Boot 4 (and pulls `flyway-core`). |
| **add** | `org.flywaydb:flyway-mysql` | Required for Flyway against MySQL 8. |
| **add** | `io.micrometer:micrometer-registry-prometheus` (`runtimeOnly`) | Prometheus scrape format for `/actuator/prometheus`. |
| **remove** | `org.springframework.boot:spring-boot-starter-webflux` (+ `-webflux-test`) | Unused; the services are servlet MVC. |
| **remove** | `org.springframework.boot:spring-boot-starter-webservices` (+ `-webservices-test`) | SOAP; unused. |
| **remove** | `org.postgresql:postgresql`, `org.testcontainers:testcontainers-postgresql` | Standardised on MySQL. |
| **plugin remove** | `org.graalvm.buildtools.native` | Unused; forced an AOT build step (which also surfaced the springdoc incompatibility). |
| **build script add** | `springBoot { buildInfo() }` | Writes `build-info.properties` so `/actuator/info` reports the version/build time. |

Kept but still unused (not in scope this pass): `spring-boot-starter-restclient`
(likely near-term need for service-to-service calls), `spring-modulith-starter-*`,
the `asciidoctor` plugin + `spring-restdocs` test deps, the `dgs.codegen` plugin.

---

## Port map

| | app | MySQL (host) |
|---|---|---|
| Allergen monolith | 8080 | 3306 |
| IngredientCatelog | 8082 | 3307 |
| RecipeCatelog | 8083 | 3308 |

---

## Verification performed

- `./gradlew test` — **IngredientCatelog 8/8**, **RecipeCatelog 8/8** (Testcontainers MySQL): `contextLoads`, `TagSearchIntegrationTest` (3), `OpsEndpointsTest` (4).
- `docker build` — both images build.
- `docker compose --profile full up --build` — both: MySQL → healthy → app UP in ~40s. Exercised with curl: create ingredient/recipe with mixed-case tag names (normalised), `match=all` / `match=any` searches (correct totals), tag prefix autocomplete, `/actuator/info`, `/openapi.yaml`. Both stacks torn down with `down -v`.
- A temporary `SchemaExportIT` test was used in each service to capture Hibernate's generated DDL for `V1__init.sql`, then deleted.

---

## Not done / suggested follow-ups

- Spring Data `Page` is serialized raw (`content`, `totalElements`, `pageable`, …). A stable `PagedModel` or a hand-rolled envelope would be a cleaner published contract.
- The `asciidoctor` + `spring-restdocs` wiring is still present and unused.
- No auth on any endpoint (including `/actuator`).
- `V1__init.sql` uses Hibernate's `AUTO` id strategy (`*_seq` table per entity, `increment_size` 50). Fine, but an explicit `IDENTITY`/`SEQUENCE` choice per entity would be clearer.
- No CI wiring for the new Docker builds.
