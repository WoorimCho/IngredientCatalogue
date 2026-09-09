# Catalogue microservices — scaffolding change log

**Date:** 2026-08-29
**Scope:** `IngredientCatalogue` and `RecipeCatalogue` (sibling repos). This file lives
in `IngredientCatalogue` but documents both.

Work was done in three passes:

1. **Tag scaffold** — a reusable `Tag` entity, the entity↔tag join table, and a
   tag-search endpoint, built in `IngredientCatalogue` as the reference and mirrored
   into `RecipeCatalogue`, plus the supporting fixes needed to make each service
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
single `Ingredient` entry in this catalogue represents both. There is no separate
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
  applies; whether a recipe produces/uses an entry stays a Recipe-catalogue
  concern (no field here).
- `RecipeCatalogue` already references entries by id via `ingredientIds`; that now
  covers everything, so no rename or second id set is needed there.
- With Food folded in, **IngredientCatalogue is feature-complete for its v1 scope**
  (entity storage + reusable, editable, searchable custom tags). Remaining work
  is cross-cutting (see the roadmap), not catalogue-specific.

The historical record below (the original three-pass scaffold) is unchanged.

---

## Update — 2026-08-29 (later): RecipeCatalogue — full recipe model

The scaffold's flat `Recipe` (one TEXT `recipeSteps` blob + a `Set<Long>
ingredientIds`) was replaced with the model from the roadmap.

### New files (RecipeCatalogue)

| File | Why |
|---|---|
| `Model/RecipeStep.java` | A step is `{position, text, tools}`. Child `@Entity` with `@OrderColumn` on the parent; `tools` is an `@ElementCollection Set<String>` **per step**. A child entity (not an `@Embeddable` in an `@ElementCollection`) because JPA can't nest an element collection (`tools`) inside an embeddable inside an element collection. |
| `Model/RecipeIngredient.java` | The per-use metadata a flat `Set<Long>` can't hold: `quantity` (free text), `optional`, `replaceable`. `ingredientId` stays a plain `Long` — no cross-service FK. |
| `Model/IngredientReplacement.java` | "If replaceable, list the replacements; if a replacement is makeable, link its recipe." → `ingredientId` (the substitute) + nullable `recipeId` (soft link back into this catalogue, no FK). Child of `RecipeIngredient`. |
| `Repositories/RecipeSpecifications.java` | Composable `name` / `ingredient` / `tag` filters for the search endpoint, ANDed. "Has all tags" is **one inner join per tag**, not `group by … having`, so Spring Data's generated count query stays well-behaved. |
| `Dto/RecipeStepDto.java`, `Dto/ReplacementDto.java`, `Dto/RecipeIngredientDto.java`, `Dto/RecipeIngredientResponse.java` | Request/response records for the nested graph. `optional` / `replaceable` are boxed `Boolean` (with `isOptional()` / `isReplaceable()` helpers) — the JSON library under Boot 4 rejects records with **missing primitive** components, so boxing lets the fields be omitted (absent = false). |

### Rewritten (RecipeCatalogue)

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
| Recipes **inherit tags** from their foods/ingredients | Needs a call into the Ingredient catalogue. Belongs in the BFF's cross-service composition (roadmap Phase 2), not this service. |
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

RecipeCatalogue stores `ingredientId` values; IngredientCatalogue owns the ingredient
data. The "join" is done at read time in the BFF (the monolith), not in SQL and
not by either catalogue calling the other.

### IngredientCatalogue — a batch lookup

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
| `CatalogueClientConfig.java` | Two `RestClient` beans, base URLs from `recipe-catalogue.url` / `ingredient-catalogue.url` (localhost defaults; overridden with compose/k8s service names later). |
| `RecipeCatalogueClient.java` | `getRecipe(id)`. Nested records are this app's view of RecipeCatalogue's `RecipeResponse` — only the fields consumed; unknown fields ignored. |
| `IngredientCatalogueClient.java` | `byIds(ids)` → `Map<Long, Ingredient>` via the new batch endpoint. |
| `RecipeCompositionService.java` | Fetch recipe → collect every catalogue id (line items **and** their substitutes) → one `byIds` call → stitch `name` + `tags` onto each line. Also unions the line ingredients' tags into `inheritedTags`. |
| `ComposedRecipeController.java` | `GET /bff/recipes/{id}` → the composed `RecipeView`. |
| `BffExceptionHandler.java` | Downstream 404 → 404; any other downstream failure → 502. Scoped to the BFF controller so it doesn't touch the app's Thymeleaf error handling. |
| `application.properties` | `recipe-catalogue.url` / `ingredient-catalogue.url` added. |
| `src/test/java/.../bff/RecipeCompositionServiceTest.java` | `MockRestServiceServer` on both clients — asserts name resolution, `inheritedTags` union, an unresolved id → `{resolved:false, name:"unknown ingredient (#id)"}`, and downstream 404 → `HttpClientErrorException.NotFound`. |

### Design choices

- **The id is the connection.** `RecipeIngredient.ingredientId == Ingredient.id` in the other service — an unenforced foreign key. The join moves from the database to the BFF.
- **Batch, don't N+1.** One `by-ids` call resolves a whole recipe's worth (primaries + replacements).
- **Tolerate dangling ids on read.** An id that no longer resolves becomes `resolved:false` with a placeholder name — no error. No write-time validation that referenced ids exist (yet).
- **Tag inheritance lives here.** "Recipe inherits its ingredients' tags" is `inheritedTags` in the composed view — a read-time union, exactly why it was deferred out of RecipeCatalogue.
- **Failures degrade sensibly.** Downstream unreachable / 5xx → `502 Bad Gateway` from the BFF, not a 500.

### Deferred, with reasons

| Item | Why not now |
|---|---|
| **Cache** ingredient lookups in the BFF | Ingredients change rarely; a short-TTL `@Cacheable` on `byIds` is the obvious next step once call volume matters. |
| **Write-time validation** of referenced ids | Costs a round trip and has a TOCTOU gap. Tolerating dangling refs on read is simpler and more resilient to start. |
| **Events** (catalogue publishes "ingredient renamed", BFF/Recipe keeps a local read model) | Only worth it if read-time composition becomes a bottleneck. |
| **Compose the Thymeleaf pages** from the catalogues | The BFF endpoint is JSON only; the UI still uses local repos. That switch is the Phase 3 cutover. |

### Verified

`./gradlew test` in IngredientCatalogue — **9** (1 `contextLoads`, 4 ops, 4
integration incl. `by-ids`). `mvn test -Dtest=RecipeCompositionServiceTest` in
the monolith — **2**, green. **End to end with all three services in Docker:**
seeded two ingredients in IngredientCatalogue and a recipe in RecipeCatalogue
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
service boundary, same as the catalogues.

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
- Same parity kit as the catalogues: Flyway (`V1` schema + `V2` seed),
  `ddl-auto=validate`, actuator (`health`/`info`/`prometheus`), `buildInfo()`,
  static `openapi.yaml`, Dockerfile + profile-gated compose `app`,
  Testcontainers. Same Boot-4 gotchas already documented (sliced test starters,
  `spring-boot-flyway` module for the Flyway autoconfig).

### Deferred, with reasons

| Item | Why not now |
|---|---|
| **Session / token issuance** and **who may read/modify account N** | The inter-service auth mechanism is still an open decision. `authenticate` is the primitive; enforcement is the BFF's job once that's decided. |
| **"Search my favourite recipes"** | The User service holds the favourite recipe ids; turning that into recipe results is a BFF call to RecipeCatalogue (`GET /api/recipes?…` filtered by those ids). |
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
| `Projects/compose.yaml` | `docker compose up --build` → `mysql` + `ingredient-catalogue` + `recipe-catalogue` + `user` + `goodnight` + `bff`, one network, service-name DNS. |
| `Projects/infra/mysql-init/01-databases.sql` | Runs once on first MySQL init: creates `mydatabase` / `ingredient_catalogue` / `recipe_catalogue` / `user_service` and grants `myuser` on each. |

### Decisions this settles

- **DB topology: one MySQL instance, one schema per service.** Simplest thing
  that works on a dev box; the init script owns schema creation, each service's
  `SPRING_DATASOURCE_URL` points at `mysql:3306/<its_schema>`.

### Wiring

- BFF env: `INGREDIENT_CATALOGUE_URL=http://ingredient-catalogue:8082`,
  `RECIPE_CATALOGUE_URL=http://recipe-catalogue:8083`,
  `GOODNIGHT_URL=http://goodnight:8085` — the localhost defaults in
  `application.properties` are overridden with compose service names.
- GoodNight's feign callback → `http://bff:8080/response`.
- **Health-gated startup:** everything waits for `mysql` healthy; the `bff` waits
  for all three catalogues *healthy* (not just started), so `GET /bff/recipes/{id}`
  works the instant `up` returns. Healthchecks: `mysqladmin ping` (mysql),
  `curl -fsS /actuator/health` (catalogues + user — the `eclipse-temurin:17-jre`
  image ships curl), busybox `wget --spider /` (bff — the monolith image is
  alpine and has no actuator), and GoodNight's own baked `HEALTHCHECK`.
- All ports still published (8080/8082/8083/8084/8085, 3306) for direct curl.
- The per-service `compose.yaml` files (with the `--profile full` app service)
  stay as-is for isolated single-service dev.

### Verified

`docker compose up -d` from `Projects/` → all six containers report **healthy**
(bff last, ~40s after the catalogues). Then: seeded an ingredient in
`ingredient-catalogue` and a recipe in `recipe-catalogue` referencing it;
`GET localhost:8080/bff/recipes/1` returned the composed view with the
ingredient resolved to `rice noodles` + `inheritedTags:["allergen:none"]` —
i.e. `bff → recipe-catalogue → ingredient-catalogue` all over the compose network by
service name. Registered a user, read the seeded restriction catalogue. One
MySQL, four schemas (`show databases` confirmed). Torn down with `down -v`.

First `up --build` is slow — five images, and the monolith + GoodNight build
with plain Maven (no layer cache). Rebuilds are fast.

---

## Update — 2026-08-29 (later): BFF ↔ User composition

The BFF now personalises a recipe for an account, and lists an account's
favourites.

### RecipeCatalogue — batch lookup

Mirror of IngredientCatalogue's: `GET /api/recipes/by-ids?id=1&id=2` →
`List<RecipeResponse>` (unknown ids omitted, 500 cap). `RecipeService.getByIds`
via `findAllById`. + openapi + one integration test. **12 tests** now.

### Allergen-Information-System — new BFF pieces

| File | Change |
|---|---|
| `bff/UserServiceClient.java` (new) | `restrictions(id)`, `favoriteRecipeIds(id)`, `favoriteAlternatives(id)` against the User service. |
| `bff/CatalogueClientConfig.java` | Third `RestClient` bean; `user-service.url` (localhost default). |
| `bff/RecipeCatalogueClient.java` | `getRecipesByIds(ids)` for the favourites list. |
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
  tells the UI "this account would swap in X" — RecipeCatalogue is untouched.
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

**What's still open (deliberately):** direct calls to the catalogue / User
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

All four services (IngredientCatalogue, RecipeCatalogue, User, GoodNight) are
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
| `Projects/infra/prometheus/prometheus.yml` | Scrapes `/actuator/prometheus` on `bff` / `ingredient-catalogue` / `recipe-catalogue` / `user` (GoodNight has no actuator). A `relabel` sets a `service` label from the target host. |
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
`[recipe-catalogue,<traceId>,<spanId>]` appears in logs, no longer `,,`).

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
| IngredientCatalogue | `IngredientRepositoryDataJpaTest` (3) | `findByAllTagNames` (`group by … having count(distinct t.id)`), `findByAnyTagName`, `findByTags_IdIn` |
| RecipeCatalogue | `RecipeRepositoryDataJpaTest` (3) | `findMaxVersion` (`coalesce(max(version), 0)`), `findByTags_IdIn`, the `hasAllTags` / `hasAnyTag` Specifications (one inner join per tag) |
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
`TestcontainersConfiguration` (and its dev `compose.yaml`, and RecipeCatalogue's
test config) were still on `mysql:latest` = now 9.x. The monolith's older
Testcontainers injects a legacy `innodb_log_file_size` tuning that MySQL 9
removed, so the container refused to boot and
`AllergenInformationSystemTests.contextLoads` failed. All four services + both
compose layers now say `mysql:8.4` (the LTS the top-level `compose.yaml` already
used).

**Full suites — all green:**

| Service | Tests | Command |
|---|---|---|
| IngredientCatalogue | 12 | `./gradlew test` |
| RecipeCatalogue | 15 | `./gradlew test` |
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
  the two catalogue `build.gradle.kts`; **7 duplicate `tools.jackson` deps** from
  the monolith `pom.xml`.
- **Dockerfile cache ids** — each catalogue's Gradle build cache mount got a
  distinct `id=`, so `docker compose build` (parallel) no longer deadlocks on a
  shared `/root/.gradle/caches/journal-1` lock.

### CI  (files written; can't run here)

The services are in separate git repos, so it's one workflow per repo, not one
pipeline:
- `Allergen-Information-System/.github/workflows/ci.yml` — `mvnw test` on
  push/PR; build + push `ghcr.io/<repo>:<sha>` on push to `main`.
- `Projects/infra/ci/gradle-service-ci.yml` — the Gradle equivalent, to copy
  into each catalogue / User repo.
- `Projects/infra/ci/README.md` — the per-repo setup + the "monorepo or
  submodules" prerequisite for a single pipeline.

### Deferred (documented in TODO.txt)

Contract tests and the `Page` → stable-envelope change — both landed on
2026-09-08, see the next section.

---

## Update — 2026-09-08: Phase 2 closeout

The four items left open after 2026-08-30: the Boot 4.1.1 pin, the page
envelope, contract tests, and per-repo CI.

### Spring Boot 4.1.1 pin

IngredientCatalogue and RecipeCatalogue bumped `4.1.0` → `4.1.1` (User was already
there). All suites green on 4.1.1.

### Tracing span export — fixed by swapping the Zipkin sender

The 4.1.1 pin (the "await 4.1.x+" option) was tried first and **did not** fix
export. Rebuilt the images, ran `mysql + zipkin + ingredient-catalogue +
recipe-catalogue + user`, drove traffic: Zipkin's `/api/v2/services` stayed empty
and every service logged `Dropped N spans due to ConnectException`:

```
java.net.ConnectException
  at o.s.boot.zipkin.autoconfigure.ZipkinHttpClientSender.postSpans(...:59)   [spring-boot-zipkin-4.1.1.jar]
Caused by: java.nio.channels.ClosedChannelException
  at sun.nio.ch.SocketChannelImpl.ensureOpen / beginConnect
```

`curl -XPOST http://zipkin:9411/api/v2/spans` from the same container returns
**202** — not DNS, not the network, not Zipkin. It is `spring-boot-zipkin`
4.1.x's JDK-`HttpClient`-based `ZipkinHttpClientSender` closing its channel
before connect (runtime is Temurin **17.0.20**, so not a stale JDK). Removing
`-Djava.net.preferIPv4Stack=true` made no difference (reverted).

**Fix:** each of the three services now declares its own span sender —

| Change | Where |
|---|---|
| `implementation("io.zipkin.reporter2:zipkin-sender-urlconnection")` (version from the `zipkin-reporter` BOM Boot already imports) | the three `build.gradle.kts` |
| `Config/TracingSenderConfig.java` — `@Bean BytesMessageSender` → `URLConnectionSender.create(<management.zipkin.tracing.endpoint>)` | each service |

Boot's `httpClientSender` bean is `@ConditionalOnMissingBean(BytesMessageSender.class)`,
so the explicit bean replaces it with the plain `HttpURLConnection` sender — the
same one the BFF (Boot 3.5) already exports through. **Verified:** rebuilt, ran
the stack, drove traffic → `/api/v2/services` returns
`["ingredient-catalogue","recipe-catalogue","user"]`, spans present for all three,
zero `Dropped … spans` lines in any log.

### Page envelope — `PageResponse<T>`

The catalogue list endpoints (`GET /api/ingredients`, `/api/tags`,
`/api/recipes`) returned Spring Data's `Page` directly, so the JSON carried its
internal shape — a nested `pageable`, a `sort` object, `number` vs `page` — none
of it contract, and it has shifted between Spring versions.

New `Dto/PageResponse.java` in each catalogue: a record with exactly
`content, page, size, totalElements, totalPages, first, last, numberOfElements,
empty`. Controllers now return `PageResponse.of(service.search(...))`; the
service layer still deals in `Page<T>`. `openapi.yaml`'s `PageEnvelope` schema
was rewritten to match (and `required:`-marked, since the record always emits
every field). No BFF change — the BFF only calls `/by-ids` and `/{id}`, never a
paged endpoint. The existing integration tests assert on `"totalElements":`
only, so they were unaffected.

### Contract tests

`swagger-request-validator-mockmvc:2.46.1` (`testImplementation`) in all three
services. Each gets an `OpenApiContractTest` (`@SpringBootTest` +
`@AutoConfigureMockMvc` + Testcontainers) that builds an
`OpenApiInteractionValidator` from the checked-in `static/openapi.yaml` and runs
~8–12 real request/response pairs through MockMvc, asserting **both** directions
with `.andExpect(openApi().isValid(validator))`:

| Service | Interactions covered | Tests |
|---|---|---|
| IngredientCatalogue | list + tag-search (page envelope), get, 404, create, 400, `by-ids`, tag list + create | 9 |
| RecipeCatalogue | list + search (page envelope), full-model create, get, 404, 400 (replaceable w/o substitutes), `by-ids`, tag list + create | 9 |
| User | register, 400 (duplicate username), get + 404, authenticate + 401, restrictions replace/read, favourites (recipes + alternatives), restriction catalogue + 404 | 10 |

So a controller that drifts from its published contract — renamed field, changed
status, reshaped envelope — now fails the build. All 28 pass. A separate
BFF↔catalogue wire test wasn't added: `RecipeCompositionServiceTest` already pins
those client records against `MockRestServiceServer`.

**What the validator forced fixing along the way:**

- **`openapi: 3.1.0` → `3.0.3`** in all three specs. `swagger-request-validator`'s
  bundled `swagger-parser` reads a 3.1 `type: array` parameter schema as the
  2020-12 `types: ["array"]`, which its parameter logic doesn't recognise →
  "*Multiple values found for parameter 'tag' but it is not an array*". The specs
  only ever used 3.0-compatible constructs (`nullable: true`, singular
  `example`), so the version bump down is a no-op for content.
- **Flattened every `allOf`** (`Page*Response`, `RecipeIngredientOutput`,
  `FavoriteAlternativeResponse`). The validator treats each `allOf` branch as a
  *closed* object, so `{allOf: [Base, {extraField}]}` rejected the merged
  instance from both sides. Each is now one self-contained `object` schema.
- **RC's `openapi.yaml` didn't parse at all** — two flow-mapping `description:`
  values had unquoted `,` / `()` (`{ type: string, description: Free text ("2
  cups", "a pinch"). }`). Never caught before because nothing had parsed the
  hand-written YAML since springdoc was dropped. Quoted.

### CI — a workflow in every repo

IngredientCatalogue and RecipeCatalogue became their own git repos, so each of the
three Gradle services got `.github/workflows/ci.yml` (from
`Projects/infra/ci/gradle-service-ci.yml`): `./gradlew test` on push/PR, then
build + push `ghcr.io/<repo>:<sha>` + `:latest` on push to `main`/`master`.
With the monolith's existing workflow that's one per repo. `infra/ci/README.md`
updated: what's left is pushing the repos to GitHub and enabling package writes.

---

## Update — 2026-09-08: Phase 3 — cutover (core)

**Decision: no UI.** The monolith's server-rendered Thymeleaf CRUD was a toy
(`Food{id,name}`, `Ingredient{id,name}`, `Recipe{id, food_fk, ingredient_fk}`) —
far thinner than the services that replace it. Rebuilding it against the rich
models would be throwaway work. So the product is now the **catalogue JSON APIs +
the `/bff/**` composition endpoints**; the monolith is **BFF + auth + GoodNight +
a landing page**, and has **no database**.

Retired code is **commented out in place, not deleted** (per request) — each file
carries a restore banner. Delete for real once the cutover has settled.

### Monolith (`Allergen-Information-System`)

| File | What |
|---|---|
| `Models/{Food,Ingredient,Recipe}.java`, `Repositorys/{Food,Ingredient,Recipe}Repository.java`, `Services/BasicService.java` + `BasicServiceImpl.java` | Whole body prefixed `//`, `package` line kept — compiles to nothing. |
| `Controllers/MainController.java` | Food/Ingredient/Recipe `@*Mapping` methods wrapped in `/* … */`; the `BasicServiceImpl` field + `Food`/`Ingredient` imports commented. **Kept:** `home()` (`/`, `/home`), `/response`, `/request` (GoodNight), the `GoodNightRestClientImpl` field. |
| `src/main/resources/templates/{Food,Ingredient,Recipe,Error}/*.html` (17) | `<!-- RETIRED … -->` banner prepended; body untouched. `home.html` still live. |
| `pom.xml` | `spring-boot-starter-data-jpa`, `mysql-connector-j`, `spring-boot-docker-compose`, `spring-boot-testcontainers`, `testcontainers:junit-jupiter`, `testcontainers:mysql` → `<!-- … -->`. |
| `application.properties` | `spring.datasource.*`, `spring.jpa.*`, all Hikari lines, `spring.mvc.hiddenmethod.filter.enabled` → `#`. Kept: `server.port`, the `*-catalogue.url` / `user-service.url`, actuator, tracing. |
| tests | `BasicServiceImplTest` (13), `TestcontainersConfiguration`, `TestAccessingDataMysqlApplication` commented out. `AllergenInformationSystemTests` lost its `@Import(TestcontainersConfiguration)` — it's now a plain "does the BFF context start with no DB" smoke test. |
| `compose.yaml` (monolith-local) | `mysql` service + the `app` datasource env / `depends_on: mysql` commented. |
| `../compose.yaml` (top-level) | `bff` lost its `SPRING_DATASOURCE_*` env and `depends_on: mysql` (the three catalogues still need `mysql`). |

**Data migration: skipped.** `mydatabase` held only hand-typed CRUD test rows,
and the old `Recipe` (a food+ingredient pair) has no place in RecipeCatalogue's
model. Nothing worth moving; and with no local entities the monolith has no
schema, so the "add Flyway to the monolith" step is moot.

**Verified:** `mvn clean test` green — **11 tests**
(`AllergenInformationSystemTests.contextLoads` + `GoodNightRestClientImplTest` 2
+ `bff/ComposedRecipeControllerSecurityTest` 4 + `bff/RecipeCompositionServiceTest`
4). `contextLoads` now proves the BFF starts with **no datasource on the
classpath**.

---

## Update — 2026-09-08: Phase 3 — version alignment + naming cleanup

### Monolith → Spring Boot 4.1.1

Parent `3.5.0` → `4.1.1`, so all four services are on the same line. After the
cutover the BFF is small, so the surface was manageable:

| Change | Why |
|---|---|
| `spring-boot-starter-web` → `spring-boot-starter-webmvc` | Boot 4 module split. |
| **+** `spring-boot-starter-restclient` | Boot 4 moved `RestClient` support (and the auto-configured `RestClient.Builder` bean the BFF clients inject) into its own module — without it `contextLoads` failed with "No qualifying bean of type `RestClient.Builder`". |
| `org.springframework.security:spring-security-test` → `spring-boot-starter-security-test`; **+** `spring-boot-starter-webmvc-test` | Boot 4 test-slice modules (`@WebMvcTest` moved to `org.springframework.boot.webmvc.test.autoconfigure`). |
| tracing: `micrometer-tracing-bridge-brave` + `zipkin-reporter-brave` → `spring-boot-micrometer-tracing` + `-brave` + `micrometer-tracing-bridge-brave` + `spring-boot-zipkin` + `zipkin-sender-urlconnection` | Same Boot-4 tracing split + the same `ZipkinHttpClientSender` `ClosedChannelException` bug the catalogues hit. New `config/TracingSenderConfig.java` (mirrors the catalogues) swaps in the `URLConnection` sender. |
| `com.fasterxml.jackson.databind.*` → `tools.jackson.databind.*` | Boot 4 defaults to Jackson 3. Affected `MainController`, `GoodNightRestClientImpl`, `GoodNightRestClientImplTest`. |

`mvn test` green — 11 tests (`AllergenInformationSystemTests.contextLoads` +
GoodNight 2 + `bff/*` 8). Version alignment across a shared parent POM/BOM was
considered and skipped: there's one Maven module and three Gradle ones, so a
shared parent buys little.

### `Catelog` → `Catalog`

The two catalog services were misspelled throughout — `git mv`'d the package
directories, renamed the entrypoint classes
(`{Ingredient,Recipe}CatelogApplication`, the `*Tests`, the `Test*Application`),
`sed` the package declarations / imports / identifiers across every `.java`, and
updated `settings.gradle.kts` (`rootProject.name`), `build.gradle.kts`
(`description`, codegen `packageName`), `application.properties`
(`spring.application.name`), each `Dockerfile` (Gradle cache-mount `id=`),
`HELP.md`, and each `openapi.yaml`'s `info.description`. The compose *service
names* were already `ingredient-catalog` / `recipe-catalog` — only the `build:`
paths and the top-level project directory names change (directory renames left
for the user — the session can't rename its own working dirs).

The BFF already spelled it right (`RecipeCatalogClient`,
`IngredientCatalogClient`, `recipe-catalog.url`), so no monolith code changed for
this.

> **Superseded 2026-09-09** — the user chose the Canadian spelling *Catalogue*.
> Everything below and every reference in this file now reads *Catalogue*; see
> the final section for that wire-level pass.

### Still open in Phase 3

- Monolith `Repositorys/` → `Repositories/` (all three files are commented-out
  dead code now, so this is cosmetic).
- `com.allergen_info_service` vs `com.example.*` — decide whether to unify the
  root package. Left as-is.

---

## Update — 2026-09-08: Phase 4 — calculator foundations

The nutrition / calorie / portion calculators (BFF, next) need two things that
didn't exist: a machine-readable quantity on each recipe line, and per-ingredient
nutrition. Both landed this round; the calculators come next.

### RecipeCatalogue — structured quantities

`RecipeIngredient` kept its free-text `quantity` (now explicitly "for display")
and gained **`amount`** (`Double`) + **`unit`** — a new `Model/Unit` enum stored
`@Enumerated(STRING)`:

- **MASS** → grams: `mg, g, kg, oz, lb`
- **VOLUME** → millilitres: `ml, l, tsp, tbsp, cup, fl_oz`
- **COUNT**: `piece, clove, slice, pinch` (no mass/volume equivalent — the
  calculators will report those lines as "not weighable")

Each carries `dimension()` and `toBase(amount)`. `Unit.parse` is lenient
(case-insensitive, trims, accepts `"grams"` / `"tablespoon"` / `"c"` aliases);
an unknown token → `IllegalArgumentException` → 400. The service rejects an
amount without a unit and vice-versa. `V2__recipe_ingredient_structured_quantity.sql`
adds the two nullable columns. DTOs + `openapi.yaml` updated; `UnitTest` (4) +
two rejection tests added. **30 tests green.**

### IngredientCatalogue — nutrition

- **`Model/Nutrition`** — an `@Embeddable` on `Ingredient` (`@Embedded`, all
  columns `nutr_*`, all nullable): `basisGrams` (the reference amount, default
  100) + `kcal, proteinG, carbsG, fatG, fiberG, sugarG, sodiumMg`. `isPresent()`
  tells an empty embed from real data.
- **`PUT /api/ingredients/{id}/nutrition`** sets/replaces it (empty body clears);
  `IngredientRequest`/`Response` also carry an optional `nutrition`. On update a
  missing `nutrition` key leaves it untouched.
- **`Model/NutritionReference` + `GET /api/nutrition-reference[?name=]`** — a
  static, Flyway-seeded table (`V3`, 44 common foods, per 100 g, USDA-style
  approximations). The BFF / UI uses it to offer "fill from reference".
- `V2__ingredient_nutrition.sql` adds the `nutr_*` columns + the reference table;
  `V3` seeds it. New tests: nutrition round-trip, `nutritionReferenceSeedLoaded`,
  contract-test interactions.

**Contract-test snag (again):** `nutrition` is a nested object that can be
absent. OpenAPI 3.0's "object or null" is `allOf: [{$ref}] + nullable: true`,
which `swagger-request-validator` does **not** honour — it validated the `null`
against `type: object` and failed. Fix: `@JsonInclude(NON_NULL)` on
`IngredientResponse` + `NutritionDto` so an unset field is *omitted*, not
`null`; the `openapi.yaml` field is then a plain `$ref` (absent is valid because
it isn't `required`).

### BFF — the calculators

Three endpoints on a new `bff/RecipeCalculatorController` (under `/bff/**`, so
session-gated like the rest; none personalise). All compose the same way: fetch
the recipe from RecipeCatalogue, resolve its line ingredients in one
`/api/ingredients/by-ids` call, do the arithmetic the services can't.

| Endpoint | What |
|---|---|
| `GET /bff/recipes/{id}/nutrition?servings=N` | per-line contribution + `total` + `perServing`. A line is **counted** only if it has a mass `amount`+`unit` *and* its ingredient has nutrition data; otherwise it's listed with a `note` (`unit 'cup' is not a weight`, `no nutrition data`, `unknown ingredient`, `no structured amount`) and appears in `notCounted`. |
| `GET /bff/recipes/{id}/calories?servings=N` | the kcal slice — `totalKcal`, `perServingKcal`, `countedLines` / `totalLines`. |
| `GET /bff/recipes/{id}/portions?scale=X` **or** `?anchorIngredientId=&anchorAmount=&anchorUnit=` | every line's numeric `amount × scale`. `scale` is given directly, or derived from "I want this much of that line" (anchor); the two are mutually exclusive, and the anchor unit must be the same *dimension* as the recipe line's. Free-text-only lines pass through unscaled with `scaled=false`. |

Supporting pieces:

- `bff/Units.java` — the BFF's compact copy of RecipeCatalogue's `Unit` table
  (cross-service code can't be shared): `dimension(token)` and
  `toGrams` / `toBase`. RecipeCatalogue's enum stays the source of truth for what
  units exist.
- `RecipeCatalogueClient.Ingredient` gained `amount` + `unit`;
  `IngredientCatalogueClient.Ingredient` gained a nested `Nutrition` record.
- `BffExceptionHandler` now also advises `RecipeCalculatorController` and maps
  `IllegalArgumentException` → 400 (bad `scale` / mismatched anchor).

Tests: `RecipeCalculatorServiceTest` (6 — the math against stubbed catalogues,
including the not-weighable / no-data / unknown-id paths and the anchor errors)
+ `RecipeCalculatorControllerTest` (4 — session required, 200, 400, 502).
Monolith suite **21 green**. Not yet exercised against the live Docker stack —
the catalogue wire formats are contract-tested and the client records match them
field-for-field.

### Random recipe selector

`GET /api/recipes/random` on RecipeCatalogue — one random recipe, optionally from
a filtered set (same `name` / `tag` / `match` / `ingredientId` params as the
list). 404 when nothing matches. `RecipeServiceImpl.search`'s spec-building was
extracted into a shared `buildSpec(...)`; `random` counts the matching set and
picks `PageRequest.of(random(0..count), 1)` — no `ORDER BY RAND()`, no native
SQL. RC suite **32 green**.

### CSV bulk import

`POST /api/ingredients/import` and `POST /api/recipes/import` — both
`multipart/form-data` (part `file`), both always 200 with an `ImportResult`
(`rows` / `imported` / `skipped` / `errors[{line, message}]`). A bad row is
reported, never fatal: each row is created in its own transaction
(`@Transactional(propagation = NOT_SUPPORTED)` on the importer so the per-row
`service.create` transactions stay independent — one constraint violation
doesn't poison the rest).

| Catalogue | CSV columns |
|---|---|
| IngredientCatalogue | `name` (req), `tags` (`;`-sep), and any of `basisGrams,kcal,proteinG,carbsG,fatG,fiberG,sugarG,sodiumMg` → nutrition |
| RecipeCatalogue | `name` (req), `creator`, `tags` (`;`-sep), `ingredients` (`;`-sep tokens `<id>` / `<id>:<amount>:<unit>` / `…:opt`). Steps aren't imported — `PUT /api/recipes/{id}` afterward |

- A hand-rolled `Services/Csv.java` (RFC-4180-ish: quoted fields, `""` escapes,
  no embedded newlines) in each service — `commons-csv` isn't in the Boot 4.1
  BOM and a shared lib can't cross the service boundary.
- `spring.servlet.multipart.max-file-size=5MB` in both.
- **Contract-test note:** `swagger-request-validator` can't reconstruct a
  multipart body from a MockMvc request (`validation.request.body.missing`), so
  the import interactions assert the `ImportResult` response shape with
  `jsonPath` instead of `openApi().isValid(...)`.
- IngredientCatalogue **28 green**, RecipeCatalogue **34 green**.

---

## Update — 2026-09-08: Phase 4 — the UI service

The front end is now its own service, `Projects/UI` (`com.example:UI`, Spring
Boot 4.1.1, Maven, **:8081**) — server-rendered Thymeleaf, no JS framework. It
holds no data and no business logic: every page is a call to a catalogue, the
User service, or the BFF, rendered. The retired monolith templates + the
cherry-blossom look were the starting point; everything was rebuilt against the
current (post-cutover) APIs.

### Shape

| Piece | Role |
|---|---|
| `config/ClientConfig` | four `RestClient` beans — one per downstream (`ingredient-catalogue` 8082, `recipe-catalogue` 8083, `user` 8084, `bff` 8080), base URLs from env. |
| `client/{Ingredient,Recipe,User,Bff}Client` | typed wrappers; records mirror each service's wire format field-for-field. `PageResult<T>` matches the shared `PageResponse` envelope. |
| `web/*Controller` | one per area — Home, Auth, Ingredient, Tag, NutritionReference, Recipe, Calculator, Account, Import. |
| `web/SessionSupport` + `CurrentUser` + `AuthInterceptor` + `GlobalModelAdvice` | the UI's own `HttpSession`; `currentUser` on every model; login-guard on `/account/**` and the calculator routes. |
| `web/UiExceptionHandler` | a downstream 4xx/5xx → a styled error page; `detail` pulled from the problem+json body. |
| `templates/` + `static/css/app.css` | native Thymeleaf fragments (`fragments/layout :: head/nav/footer`), one stylesheet. |

### Auth

The UI keeps its own session. Login = User `POST /api/authenticate` (for the
`accountId`) **and** BFF `POST /bff/login` as a form post (to capture its
`JSESSIONID`); both are stored in `CurrentUser`. Every `/bff/**` call from the
UI then relays that cookie in a `Cookie` header, so BFF composition
personalises (restriction conflicts, preferred swaps) for the right account.
Anonymous users still get everything that doesn't need an identity — the recipe
page falls back to composing names/tags UI-side via `/api/ingredients/by-ids`.

### Every service is represented

- **IngredientCatalogue** — list (tag filter, `match`), detail, create/edit,
  delete, **set nutrition** (8-field form + "look up in reference"),
  **nutrition-reference** browser, **CSV import**; tag browser (create / delete
  / merge). Nutrition shows as a per-100 g badge in the list and a table on the
  detail.
- **RecipeCatalogue** — list (name / tag / `match` / ingredient-id), detail
  (own + inherited tags, per-line badges, substitutes, numbered steps with tool
  chips), create/edit via a dynamic step/ingredient form (**structured
  `amount` + `unit`** per line), delete, **"Surprise me"** (`/random`),
  **CSV import**.
- **User** — register, login/logout, profile + password, **dietary
  restrictions** (checkbox grid grouped by kind), **favourite recipes**,
  **favourite substitutions** (add / update / remove; surfaced as ⭐ on the
  recipe).
- **BFF** — the personalised recipe view (restriction-conflict banner,
  preferred-swap markers) and all three **calculators**: nutrition
  (total + per-serving + per-line, with a "not counted" list), calories
  (stat tiles), portions (multiplier **or** anchor-ingredient).

### Bugs found wiring it up (all fixed)

| Symptom | Cause | Fix |
|---|---|---|
| `/ingredients/new`, `/recipes/new` → 500 | a ternary (`${mode=='edit' ? … : …}`) as a `th:replace` fragment parameter is evaluated in Thymeleaf's *restricted* mode → "instantiation … forbidden" | pass a plain `pageTitle` model attribute instead |
| `/recipes`, `/tags` → 500 | `th:if="${a or b}"` throws when the operands are null | `${a != null or b != null}` |
| a one-line recipe step saved as three | Spring `@ModelAttribute` binding **comma-splits** a single value bound to `List<String>` | build `RecipeForm` from a `@RequestParam MultiValueMap` (i.e. `getParameterValues`, which never splits) |
| CSV import → `NoClassDefFoundError: org/reactivestreams/Publisher` | `RestClient`'s multipart writer references `Publisher`; not on a webmvc-only classpath | add the `org.reactivestreams:reactive-streams` jar |
| logged-in recipe page lost the "80 g" per line | the BFF's composed `RecipeView.Line` predated structured quantities | add `amount` + `unit` to `RecipeCompositionService.Line` and carry them through |

The last one is the only change outside `Projects/UI`: two fields on a BFF DTO
(`RecipeCompositionService` + its `RecipeCatalogueClient` already parsed them).
`RecipeCompositionServiceTest` still 4 green; monolith unchanged otherwise.

### Ops

`Dockerfile` (multi-stage, temurin-17), a `ui` service in `Projects/compose.yaml`
(env for the four downstreams + Zipkin, `depends_on` all healthy, `:8081`,
`wget` healthcheck on `127.0.0.1`), a scrape target in `prometheus.yml`, and a
GitHub Actions job mirroring the others. Tracing uses the same
`URLConnectionSender` bean as every other service.

**Also fixed this round:** the `bff` compose healthcheck used
`http://localhost:8080` — busybox `wget` resolves `localhost` to `::1` with no
IPv4 fallback under `-Djava.net.preferIPv4Stack=true`, so the container reported
unhealthy. Both `bff` and `ui` now hit `127.0.0.1`.

### Verification

`./mvnw test` — **5 green** (`contextLoads` + 4 `@WebMvcTest` page renders).
Full stack up via `docker compose` (MySQL, Zipkin, Grafana/Prometheus, the four
services + `ui`); walked through in a browser end to end: register/login,
ingredient create + nutrition + reference lookup, tag create, recipe create with
structured quantities, all three calculators (numbers checked by hand), a
peanut restriction surfacing a conflict on Pad Thai, favourite recipe +
substitution round-trips, CSV import ("imported 2 of 2"). Catalogue + BFF images
were rebuilt first — the running ones predated the Phase 4 endpoints.

Entry point: **http://localhost:8081**.

### Follow-up — names instead of ids, and a few gaps

A second pass on the same request, driven by "don't make me know the ids":

- **Autocomplete.** A `static/js/suggest.js` progressive enhancement: any input
  with `data-suggest="/suggest/..."` grows a `<datalist>` refilled as you type.
  Three tiny JSON endpoints (`SuggestController`) proxy the catalogue searches —
  tag names, ingredient names, recipe names. Without JS the field is still plain
  text and the server resolves whatever was typed. Wired on the tag filter, tag
  merge, the ingredient name search, and the favourite-substitution form.
- **Tag merge by name.** `POST /tags/merge` now takes names *or* ids in both
  fields; `TagController` resolves each against the catalogue (a bare number is
  still an id) and flashes "no tag found for …" instead of a 500.
- **Ingredient search by name.** IngredientCatalogue's `GET /api/ingredients`
  gained a `name` fragment filter (`IngredientSpecifications.nameContains`,
  composed with the existing tag queries; spec + openapi + tests updated). The
  ingredients list page gets a name box; it also backs the autocomplete.
- **Ingredient delete from the list.** Each row has a Delete button now. Since
  ingredients and recipes are separate services with no FK, the UI first asks
  RecipeCatalogue `?ingredientId=` — if anything uses it, the delete is blocked
  with "used by N recipe(s)" rather than silently orphaning recipe lines.
- **Custom dietary restrictions.** The User service already stores restriction
  codes as free text, so this is UI-only: the restrictions page gained a "Your
  own" section (an "add custom" box, plus a checkbox per existing custom code so
  it round-trips / can be unchecked to remove).
- **Favourite substitutions by name.** The add form takes recipe / ingredient /
  swap *names* (ids still accepted); `AccountController` resolves them, and the
  table now shows names, resolved via one `by-ids` call plus a recipe fetch per
  row. Remove moved to its own `POST /favourites/alternatives/remove`.

Re-verified in the browser against the Docker stack (IngredientCatalogue + UI
images rebuilt): tag-name merge and its error path, autocomplete filling from
each endpoint, delete blocked on an in-use ingredient / succeeding on an unused
one, a custom "blue cheese" restriction saved and removed, a name-based
substitution saved and shown as ⭐ on the recipe. UI suite still **5 green**.

### Follow-up — energy density, themes, and two smaller asks

- **Recipe calculators, per gram / per 100 g.** The BFF nutrition result now
  also carries `per100g` (the totals spread back over the counted weight) and
  `countedGrams`; the calorie result adds `kcalPer100g` + `kcalPerGram`. The
  nutrition page grew a third "Per 100 g" card; the calorie page a second stat
  row (kcal/100 g, kcal/gram, counted weight). Recipe totals were already the
  sum across ingredients — this just exposes the density.
- **UI theme picker.** Four light presets (Cherry Blossom, Blueberry, Matcha,
  Lavender) as `[data-theme]` blocks in `app.css` that re-point the palette
  tokens + `--bg`. `ThemeController` writes a year-long `ui.theme` cookie from a
  `<select>` on the profile page; a tiny head script applies it before first
  paint (no flash); `GlobalModelAdvice` exposes it for the selected state.
  Per-browser, no User service change.
- **Add to the nutrition reference.** It was read-only (Flyway-seeded). Added
  `POST /api/nutrition-reference` (201, or 409 on a duplicate name) with a
  `NutritionReferenceRequest` DTO + openapi entry; the reference page grew an
  "Add a food" form (flash on success / duplicate).
- **Favourite ↔ unfavourite on the recipe page.** `RecipeController` now checks
  the account's favourite ids and passes `favourited`; the button shows
  "★ Unfavourite" (posting `action=remove`) when it's already a favourite,
  "⭐ Favourite" otherwise.

`namespace` on a tag, for the record: it's an optional free-text grouping label
("allergen", "diet") used only by `GET /api/tags?namespace=…`; it is *not*
derived from a `prefix:` in the name — that convention is cosmetic.

All verified against the rebuilt Docker stack (bff + ingredient-catalogue + ui):
theme switch + persistence, both per-100 g / per-gram views, a reference row
added and a duplicate rejected, the favourite button toggling both ways.
Tests: monolith **21**, IngredientCatalogue green, UI **5**.

**Also:** the brand emoji next to "Allergen Info" now tracks the theme
(🌸 / 🫐 / 🍵 / 🪻); `SECURITY.md` added (full assessment — 2 Critical,
4 High, 6 Medium, 7 Low); READMEs written for all six services (five had none;
the monolith's was rewritten for the post-cutover BFF role).

Entry point: **http://localhost:8081**.

---

## Update — 2026-09-09: `Catalog` → `Catalogue` (Canadian spelling, wire-level)

The user renamed the two top-level directories to the Canadian **`Catalogue`**
(`IngredientCatalogue/`, `RecipeCatalogue/`) and asked for the spelling to
propagate everywhere, wire-level included. One mechanical pass
(`perl -pi -e 's/Catalog(?!ue)/Catalogue/g'` + case variants, guarded against the
existing `catalogue`) across every `.java` / `.kts` / `.properties` / `.yaml` /
`.xml` / `.sql` / `.html` / `.md` / `Dockerfile`, plus directory and file moves:

| Layer | Change |
|---|---|
| **Java packages** | `com.example.ingredientcatalog` → `…catalogue`, `com.example.recipecatalog` → `…catalogue` — dirs `git mv`'d, `package` + `import` rewritten, DGS codegen `packageName` |
| **Entrypoint / test classes** | `IngredientCatalogApplication` → `IngredientCatalogueApplication` (+ `RecipeCatalogueApplication`, the `*ApplicationTests`, the `Test*Application`) |
| **Gradle / Spring** | `settings.gradle.kts` `rootProject.name`, `spring.application.name`, each `Dockerfile`'s Gradle cache-mount `id=` |
| **BFF client classes** | `IngredientCatalogClient` → `IngredientCatalogueClient`, `RecipeCatalogClient` → `RecipeCatalogueClient`, `CatalogClientConfig` → `CatalogueClientConfig` (files + refs + `catalogueClient` bean methods) |
| **UI** | `ClientConfig` bean names `ingredientCatalogueRestClient` / `recipeCatalogueRestClient` + `@Value` keys; `RecipeController.fromCatalogue`; template prose |
| **compose.yaml** (root + both module-local) | service keys `ingredient-catalogue` / `recipe-catalogue`, `build:` paths → `./IngredientCatalogue` / `./RecipeCatalogue`, `SPRING_APPLICATION_NAME`, `LOGGING_PATTERN_LEVEL` tags, `depends_on` keys, `INGREDIENT_CATALOGUE_URL` / `RECIPE_CATALOGUE_URL` env vars + `http://ingredient-catalogue:8082` hostnames |
| **config keys** | `ingredient-catalog.url` → `ingredient-catalogue.url` (BFF + UI `application.properties` + every `@Value`) |
| **DB schemas** | `ingredient_catalog` → `ingredient_catalogue`, `recipe_catalog` → `recipe_catalogue` — datasource URLs (compose + module-local), `infra/mysql-init/01-databases.sql` (CREATE + GRANT). **Requires `docker compose down -v`** — the schema rename orphans the old data; MySQL-init recreates the new schemas and Flyway re-migrates + reseeds. |
| **infra** | `prometheus.yml` scrape targets (`service` label derives from the hostname, so it follows automatically); Grafana provisioning; CI templates |
| **docs** | every `README.md`, `CHANGES.md`, `SECURITY.md`, `TODO.txt`; the historical "`Catelog` → `Catalog`" section above keeps its original wording with a superseded-note |

Full-repo grep afterward: **zero** occurrences of `catalog` not followed by
`ue`. Tests green post-rename — IngredientCatalogue + RecipeCatalogue
`./gradlew test`, BFF **21**, UI **5**. Then `docker compose down -v && build && up`.

The User service already used `catalogue` for its own domain term
(`restrictionCatalogue`, `catalogueCodes`) — only two prose comments there
needed touching.

---

## Update — 2026-09-09: `catalogue-common` — the duplication, deleted

The two catalogue services had ~13 byte-identical (or javadoc-only-different)
files. They now live once in a new **`Projects/catalogue-common`** Gradle
library, consumed as a **composite build** (`includeBuild("../catalogue-common")`
+ `implementation("com.example:catalogue-common")`) — no publish step, always
built from source.

### Moved into `com.example.cataloguecommon`

| | was (×2) | now |
|---|---|---|
| `Tag` `@Entity` | `…{ingredient,recipe}catalogue.Model.Tag` | `…cataloguecommon.tag.Tag` |
| `TagRepository`, `TagService`, `TagServiceImpl` | `…Repositories` / `…Services` | `…cataloguecommon.tag` |
| `TagRequest` `TagResponse` `TagNamesRequest` `MergeRequest` | `…Dto` | `…cataloguecommon.tag` |
| `PageResponse<T>` | `…Dto.PageResponse` | `…cataloguecommon.PageResponse` |
| `ApiExceptionHandler` (`@RestControllerAdvice`) | `…Controllers` | `…cataloguecommon.ApiExceptionHandler` |
| `NotFoundException` | `…Exception` | `…cataloguecommon.NotFoundException` |
| `Csv` (RFC-4180-ish reader) | `…Services.Csv` (package-private) | `…cataloguecommon.Csv` (public) |
| `TracingSenderConfig` (URLConnection Zipkin sender) | `…Config` | `…cataloguecommon.TracingSenderConfig` |

`Exception/` and `Config/` folders are gone from both services.

### The one genuinely per-service bit

`TagServiceImpl.delete` / `.merge` had to clear a tag off every owner first (no
DB cascade on the join table) — `Ingredient` in one service, `Recipe` in the
other. Extracted to a **`TagOwnerCleanup`** SPI (`detachAll` / `retag`) in
common; each service keeps a one-class `@Component`
(`IngredientTagOwnerCleanup` / `RecipeTagOwnerCleanup`, ~40 lines). That let
`TagServiceImpl` itself be shared.

### Wiring

- Each app class widens the scan: `@SpringBootApplication(scanBasePackages=…)` +
  `@EntityScan` + `@EnableJpaRepositories` add `com.example.cataloguecommon[.tag]`.
  (`@EntityScan` moved package in Boot 4.1 →
  `org.springframework.boot.persistence.autoconfigure.EntityScan`.)
- Each service still owns its own `tag` table (own schema, own Flyway `V1`);
  the shared entity just maps to whichever is in scope. No migration change.
- **Docker**: the two catalogue images now build with `context: .` (the
  `Projects/` dir) + `dockerfile: <Service>/Dockerfile`, so the composite build
  sees `catalogue-common`. A `Projects/.dockerignore` keeps the context lean.
  Module-local `compose.yaml` `app` builds set `context: ..` for the same reason.

### Result

`catalogue-common` LOC ≈ 350, replacing ≈ 700 of copy-paste. `Units` (BFF) and
the BFF↔UI mirrored DTOs were **left as-is** — sharing across the servlet/BFF
boundary would couple the BFF to a catalogue's internals, which the cutover
deliberately avoided.

Verified: `catalogue-common` `./gradlew build`; **IngredientCatalogue** +
**RecipeCatalogue** `./gradlew test` green; both Docker images build with the
new context; full `docker compose up` healthy end to end.

---

## Cross-cutting rationale

These principles drove most of the individual edits, so the per-file notes stay short.

| Principle | Consequence in the code |
|---|---|
| **Each service owns its schema.** | Separate DB per service (`ingredient_catalogue`, `recipe_catalogue`); no shared tables, no cross-service foreign keys. |
| **Cross-service references are by id, not JPA relationships.** | `Recipe` holds `Set<Long> ingredientIds` (an `@ElementCollection`), not a `@ManyToMany Ingredient`. `Ingredient.hasRecipe` was deleted — "does a recipe use this?" is the Recipe service's question. |
| **Tags are reusable and searchable.** | `Tag` is its own entity with a unique, normalised (trimmed, lower-cased) name; many-to-many to the catalogue entity; get-or-create on write; search endpoints with `match=all` / `match=any`; prefix autocomplete; merge. |
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

## IngredientCatalogue

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
| `src/main/resources/application.properties` | Added: MySQL datasource (`localhost:3307`, schema `ingredient_catalogue`, `createDatabaseIfNotExist`), `server.port=8082`, `spring.jpa.open-in-view=false`, `ddl-auto` `update`→`validate`, Flyway settings, `spring.data.rest.detection-strategy=annotated` + `base-path=/data`, actuator exposure (`health,info,prometheus`) + probe groups. | The file previously held only `spring.application.name`, so the app could not start (no datasource). Data REST is neutered so the hand-written controllers are the only surface. |
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

## RecipeCatalogue

Started from a near-empty skeleton (only `Recipe`, `RecipeRepository`,
`RecipeCatalogueApplication`, one test). It received the **same** `Tag` /
`TagRepository` / `Dto` / `Exception` / `ApiExceptionHandler` / `TagService(+Impl)`
as `IngredientCatalogue` (identical except package), plus:

### New files (Recipe-specific)

| File | Why |
|---|---|
| `Services/RecipeService.java` + `RecipeServiceImpl.java` | Mirror of `IngredientService`; `create`/`update` also persist `recipeSteps` and `ingredientIds`. |
| `Controllers/RecipeController.java` | `/api/recipes` — mirror of `IngredientController`. |
| `src/main/resources/db/migration/V1__init.sql` | Creates `recipe`, `tag`, `recipe_tag`, `recipe_ingredient_ids` (`@ElementCollection`, FK on `recipe_id` only — `ingredient_id` intentionally has no FK), `*_seq`. |
| `src/main/resources/static/openapi.yaml` | Recipe-flavoured contract (`recipeSteps`, `ingredientIds`). |
| `Dockerfile`, `.dockerignore` | Same as IngredientCatalogue; `EXPOSE 8083`. |
| `src/test/java/.../TestcontainersConfiguration.java` | New — mirrors IngredientCatalogue (MySQL `@ServiceConnection`). Did not exist before. |
| `src/test/java/.../TestRecipeCatalogueApplication.java` | New — dev entrypoint that boots with the Testcontainers config, for parity with IngredientCatalogue. |
| `src/test/java/.../TagSearchIntegrationTest.java`, `OpsEndpointsTest.java` | Mirrors; the recipe search test also asserts `ingredientIds` + `recipeSteps` round-trip. |

### Modified files

| File | Change | Why |
|---|---|---|
| `Model/Recipe.java` | Was: referenced a nonexistent `Ingredient` class and put `@ManyToMany` on a single field — did not compile. Now: `id`, `name` (unique), `recipeSteps` (`@Column(length = 20_000)` → `TEXT`), `ingredientIds` (`@ElementCollection Set<Long>`, no cross-service FK), `tags` (owning-side `@JoinTable(name = "recipe_tag", …)`), plus helper methods. | Make it compile; model ingredient references the microservice way (by id). |
| `Repositories/RecipeRepository.java` | `CrudRepository` → `JpaRepository` + `JpaSpecificationExecutor`; added `findByAnyTagName`, `findByAllTagNames`, `findByTags_IdIn`. | Same as IngredientRepository. |
| `src/main/resources/application.properties` | Was just `spring.application.name`. Now full config: MySQL datasource (`localhost:3308`, schema `recipe_catalogue`), `server.port=8083`, Flyway, `ddl-auto=validate`, `open-in-view=false`, Data REST neutered, actuator. | App could not start before. |
| `build.gradle.kts` | Same dependency/plugin changes as IngredientCatalogue (shared table below). Also **fixed a copy-paste bug**: `tasks.generateJava { packageName = "com.example.ingredientcatelog.codegen" }` → `com.example.recipecatelog.codegen`. Removed the redundant explicit `jakarta.persistence:jakarta.persistence-api` (comes with `starter-data-jpa`). Added `springBoot { buildInfo() }`. | Correctness + consistency with IngredientCatalogue. |
| `compose.yaml` | Was infra-only (`mysql:latest`, ephemeral port). Now `mysql:8.4`, host port `3308:3306`, healthcheck, and a profile-gated `app` service (`8083:8083`, schema `recipe_catalogue`). | Same rationale as IngredientCatalogue. |
| `src/test/java/.../RecipeCatalogueApplicationTests.java` | Added `@Import(TestcontainersConfiguration.class)`. | The context now needs a datasource (JPA entities + Flyway). |

---

## Shared `build.gradle.kts` dependency delta (both services)

| Action | Coordinate | Why |
|---|---|---|
| **add** | `org.springframework.boot:spring-boot-starter-data-jpa` | JPA autoconfig was genuinely missing — the app could not wire an `EntityManagerFactory`. (This is why RecipeCatalogue had a stray hand-added `jakarta.persistence-api`.) |
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
| IngredientCatalogue | 8082 | 3307 |
| RecipeCatalogue | 8083 | 3308 |

---

## Verification performed

- `./gradlew test` — **IngredientCatalogue 8/8**, **RecipeCatalogue 8/8** (Testcontainers MySQL): `contextLoads`, `TagSearchIntegrationTest` (3), `OpsEndpointsTest` (4).
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
