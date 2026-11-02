# Security assessment — Allergen Information System

Hands-on assessment against the local Docker stack (all services + infra) on
**2026-09-08**, plus source review. All test records created during testing were
deleted afterwards. This file covers the **whole system**, like `CHANGES.md`.

---

## Re-test — 2026-09-10

Full scripted pen test against the live stack + source review, covering the
2026-09-10 changes (recipe-crawler LLM free-text fallback, recipe
ingredient-count / positive-id validation, UI page-jump navigation).

**Regression pass — all prior fixes held:** C1, C2 (raw / stale / wrong-secret /
acting-user-mismatch / malformed all → 403, not 500), H1, H2, M1, M2, M5, M6,
the BFF IDOR ownership check, stored-XSS escaping, SQLi, mass assignment.

**New findings:**

| # | Sev | Finding | Fix |
|---|---|---|---|
| **N1** | 🟠 | **Inter-service HMAC secret is public.** `internal-auth.secret` defaults to `dev-internal-secret-change-me` in every tracked `application.properties` and is the value the stack runs with. Anyone with repo access can forge `X-Internal-Auth` for any method/path/acting-user → **defeats C1/C2** wherever a port is reachable. | Random secret from an untracked source; drop the `@Value` default so a missing secret fails startup. |
| **N2** | 🟠 | **SSRF via recipe-crawler.** Any logged-in UI user drives `/recipes/import-url` + `/recipes/crawl`; `RecipePageFetcher.validate` checks only the scheme + non-blank host — no loopback / RFC-1918 / rebind guard. Confirmed live: crawler fetched `ingredient-catalogue:8082`, `user:8084`, `zipkin:9411` by compose name. Blind-ish, but timing + distinct error strings are an internal port oracle. `RobotsTxt` fail-closes on a refused robots fetch (blocks closed ports, not open services). Escalates on AWS (IMDS `169.254.169.254`) and if the LLM fallback is on (internal bodies → external LLM). | Reject non-public resolved IPs (validate-time *and* on connect); cap `RobotsTxt.load()` response size. |
| **N3** | 🟡 | **Open redirect via `?next=`.** `AuthController.login`: `next.startsWith("/")` only → `//evil.example/` → `302 Location: http://evil.example/` (confirmed). Absolute `http(s)://` is correctly ignored. (Supersedes L6 "normalised safely".) | `startsWith("/") && !startsWith("//") && !startsWith("/\\") && !contains("://")`. **Fixed 2026-10-14** (a shared `SafeRedirect`; two more sinks found and fixed). |
| **N4** | 🟡 | **Unbounded request collections → DoS.** `RecipeRequest.{steps,ingredients,tags}`, `IngredientRequest.tags`, `User` `restrictions`/`favoriteRecipeIds`/`alternatives` have no `@Size`; element strings none. `POST /api/recipes` w/ 50 000 lines pegged a request thread > 40 s. Reachable by any signed caller. | `@Size(max=…)` on the collections + element strings; smaller JSON body cap. **Fixed 2026-10-14** (collections, elements, CSV, tags; the body-size cap is still open). |
| **N5** | 🟢 | **BFF actuator over-exposure.** BFF exposes `caches,metrics` beyond the other four services' `health,info,prometheus`; unauth **`DELETE /actuator/caches` → 204** (cache-flush → re-fan-out DoS). Localhost-bound. | BFF exposure list → `health,info,prometheus`. **Fixed 2026-10-14.** |
| **N6** | 🟢 | **LLM free-text fallback — prompt-injection sink (feature off by default).** Attacker-controlled page text → LLM → catalogue writes (same capability as C2, via a confused deputy). Mitigations present: input/output token caps, `json_object` mode, script/style stripped, parse-or-422. Residual: page-text egress to a 3rd party + SSRF amplification (N2). | Keep off unless needed; pair with the N2 fix; keep treating extracted fields as untrusted. |
| **N7** | 🟠 | **Anonymous writes through the UI** *(found 2026-10-14 while fixing N4)*. The UI's `AuthInterceptor` guarded only `/account/**`, `/favourites/**` and the calculators, and `SecurityConfig` is `permitAll`, so **every write route — and the import / crawl tools — worked with no session**. Confirmed live: a never-logged-in `POST /ingredients` → 302 + the row was created and then deleted by the same visitor; an anonymous `POST /recipes/import-url/preview` reached the crawler. The UI signs those calls with the shared HMAC secret, so C1/C2 never see an unauthenticated caller. It also makes **N2 reachable by anyone**, not just "any logged-in user" as N2 is worded, and puts the catalogue's integrity in every visitor's hands once `:8081` is public. | Default-deny: every non-GET needs a session except sign in / up / out and the theme switch; the new / edit forms and the import / crawl tools need one to open. |

**Still open:** N1, N2, N6, M3 (register enumeration — mitigated at the UI edge,
still distinct on the HMAC-gated User API), M4 (no rate limiting),
`/actuator/**` unauthenticated on every service, **no TLS anywhere** (the HMAC
signs method+path only — not body/query). **N3, N4, N5 and N7 were fixed
2026-10-14, and H4 (database credentials) on 2026-11-01** — see the remediation
log.

**Priority:** N1 (random secret) · N2 (SSRF guard — before any AWS deploy) ·
then the carry-overs (M3/M4/TLS).

---

## Remediation log

**2026-11-01 — Phase 5A, H4 (database credentials):**
- **H4 fixed** — the database passwords were trivial literals (a 10-character
  root password and a 6-character app password) committed in the per-service
  `compose.yaml` files, and repeated as defaults in each service's
  `application.properties` and in the READMEs' config tables — in public
  repositories. All of that is gone from the working trees:
  - `IngredientCatalogue`, `RecipeCatalogue` and `User` `compose.yaml` read
    `${MYSQL_ROOT_PASSWORD:?set in .env}` / `${MYSQL_PASSWORD:?set in .env}` —
    **no default**, so Compose refuses to start rather than fall back to a
    committed value. An *empty* value is refused too, so copying
    `.env.example` unchanged does not start the stack. `.env` is git-ignored;
    `.env.example` carries the variable names and no values.
  - The healthcheck no longer embeds the password: it reads the container's own
    `MYSQL_ROOT_PASSWORD` (`$$` keeps Compose from interpolating it).
  - `spring.datasource.password` has **no default** in the three services'
    `application.properties`: in a container it arrives as
    `SPRING_DATASOURCE_PASSWORD`; from the IDE, Spring Boot's docker-compose
    support takes it from the running MySQL container, which got it from `.env`.
  - In `Allergen-Information-System` (the BFF has had no database since Phase
    3) the credentials survived only in commented-out blocks of the published
    `compose.yaml`; the cleanup commit deletes them. `.env` is git-ignored there
    too.
  - The module `compose.yaml` files also publish their database and app ports on
    `127.0.0.1` only (they were `0.0.0.0`) — which closes the "module-local
    compose files not yet localhost-bound" item.
- **Rotated, not just hidden.** The old values sat in public repositories (and
  stay in git history), so they are treated as burned. The root stack's
  passwords were changed to fresh 192-bit random values: the MySQL image applies
  `MYSQL_*_PASSWORD` only when it first initialises a data volume, so changing
  `.env` alone would have left the published passwords working — `ALTER USER`
  was run in the live container for `root@localhost`, `root@%` and `myuser@%`.
  **Verified live:** the new passwords are accepted and the old ones are
  rejected, both right after the change and again after the container was
  recreated; all services reconnect healthy; the end-to-end smoke test passes.
- Also verified: with no `.env`, or with `.env.example` unfilled, `docker compose
  config` stops with `required variable MYSQL_PASSWORD is missing a value: set
  in .env` for each of the four compose files; `./gradlew bootRun` from the IDE
  (docker-compose support + `.env`) starts and reports healthy against the
  module's own MySQL on `127.0.0.1:3307`.
- **Not part of H4 / still open:** all three services still connect as the one
  shared `myuser` account with rights on every schema (least-privilege users per
  service are a separate hardening step); Grafana's admin password still
  defaults to `admin`; and the HMAC secret (N1) is the other published secret.
  Any database volume that was initialised with the old values keeps them until
  it is rotated or recreated (`docker compose down -v` in the module that owns
  it).

**2026-10-14 — Phase 5A, first pass (N3 / N4 / N5 / N7):**
- **N3 fixed** — the login `next` was not the only open redirect: the favourites
  `back` parameter had the identical `startsWith("/")` check, and the theme
  switch's `safeBack` refused `//host` but not `/\host`. All three now go through
  one `SafeRedirect.localPath`: a single-slash same-site path only; refuses `//…`,
  `/\…`, any backslash, control characters / whitespace (browsers strip TAB, CR
  and LF, so `/<TAB>/host` becomes `//host`), `{braces}` (`RedirectView` would try
  to expand them as URI-template variables) and anything over 2 048 chars.
  Verified live: `next=//evil.example/`, `/\evil.example` and
  `https://evil.example/` all land on `/`; `next=/recipes?name=x` is honoured.
- **N4 fixed** — every request collection and element string is bounded, aligned
  with the V1 column sizes: **recipe** — steps 200 (text ≤ 20 000, ≤ 30 tools of
  ≤ 100 chars), ingredient lines 100 (≤ 10 replacements each, `quantity` ≤ 100,
  `unit` ≤ 40), `name` / `creator` ≤ 255; **tags** — ≤ 50 per request, ≤ 100 chars
  each, enforced in `TagService.resolve` (the choke point for the JSON API, both
  CSV importers and attach-tags) plus `@Size` for a clean 400; tag-merge sources
  ≤ 50; **CSV** — ≤ 5 000 rows and ≤ 100 fields per row in `Csv.parse` (fails fast
  on a multi-million-comma line); **User** — ≤ 100 restrictions of ≤ 100 chars,
  ≤ 200 favourite substitutions per call, ≤ 1 000 favourite recipes per account;
  **crawler** — ≤ 20 seeds, URLs ≤ 2 048 chars, `creator` ≤ 255.
  Two things the finding didn't say: **User's `PUT …/restrictions` had no
  `@Valid`, so no constraint on that body had ever been applied**; and the CSV
  importers build requests without Bean Validation, so `RecipeServiceImpl.apply`
  re-checks the counts. On the UI, resolving a recipe form is one catalogue call
  per distinct ingredient name, so a 1 000-line form (reachable anonymously — see
  N7) was ~1 000 sequential calls; `RecipeForm.limitViolation()` now refuses it
  before any lookup. Verified live: a 101-line recipe form is refused with a
  clear message; an ingredient with 51 tags is not created. **Not done:** a JSON
  request-body size cap — the collections are bounded, but an arbitrarily large
  body is still parsed; do that at the ingress / ALB.
- **N5 fixed** — BFF exposure is `health,info,prometheus` (was `+caches,metrics`).
  Verified live: `GET /actuator/caches`, `DELETE /actuator/caches` and
  `GET /actuator/metrics` → **404**; `/actuator/prometheus` still carries
  `cache_gets_total`.
- **N7 found and fixed** — `AuthInterceptor` is now default-deny: reading is
  public; every request that isn't GET / HEAD / OPTIONS needs a session, except
  `/login`, `/register`, `/logout` and `/settings/theme`; the new / edit forms,
  `/import`, `/recipes/import-url`, `/recipes/crawl`, the account area and the
  calculators need one just to open. The decision is made on the route the handler
  mapping matched (`/ingredients/{id}/edit`), not the raw URL, and uses
  `getSession(false)` so a refused request no longer creates a session (this also
  closes the old note that the interceptor created a session for every anonymous
  hit). A refused write returns the user to the page they were on (`Referer`,
  path only, through `SafeRedirect`). A route-inventory test fails the build if any
  write route besides those four is left anonymous. Verified live: anonymous POSTs
  to `/ingredients`, `/recipes`, `/tags`, `/tags/merge`, `/nutrition-reference`,
  `/recipes/import-url/preview` and `/recipes/crawl`, and GETs of the new forms,
  `/import`, `/recipes/import-url` and `/recipes/crawl` all redirect to `/login`;
  the theme switch still works anonymously. **This is a behaviour change** —
  anyone who relied on creating data without an account must now register (open
  and free). To relax it, edit `LOGIN_ONLY_READS` / `ANONYMOUS_WRITES` in
  `UI/…/web/AuthInterceptor`. It narrows N2 to "any registered user", which is
  still not a real barrier: the SSRF guard is still required.
- Tests: 413 across the eight repos, all green (was 212): UI 159, RecipeCatalogue
  55, IngredientCatalogue 48, BFF 35, User 33, catalogue-common 19, recipe-crawler
  63, GoodNightWorld 1. End-to-end smoke against the rebuilt stack: 63 checks.

**2026-09-09 — Phase 5, batch 3 (UI Spring Security — H2 / M1 / M5):**
- Added `spring-boot-starter-security` to the UI + `config/SecurityConfig`:
  `anyRequest().permitAll()` (anonymous browsing stays), Spring's form login /
  basic / logout **disabled** so they don't shadow the UI's own routes.
- **H2 fixed** — CSRF on: every state-changing form POST needs the `_csrf`
  token, which Thymeleaf's `th:action` injects as a hidden field automatically
  (all 20 form templates use `th:action`; the one `fetch()` is a GET). Verified:
  a forged tokenless `POST /register` → **403**; the real form flow → 302.
  `JSESSIONID` is now `SameSite=Strict; HttpOnly` and URL rewriting
  (`;jsessionid=`) is off (`tracking-modes=cookie`).
- **M5 fixed** — `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`,
  a Content-Security-Policy (`default-src 'self'`; inline script/style still
  allowed — nonces are a later pass), `Referrer-Policy: no-referrer`.
- **M1 fixed** — session-fixation protection (Spring default) plus an explicit
  `request.changeSessionId()` in `AuthController.login` / `register` (Spring's
  own auth events don't fire for the UI's home-grown login). Verified: the
  session id changes on login.

**2026-09-09 — Phase 5, batch 2 (C1 / C2, inter-service auth):**
- Every internal caller (BFF, UI, recipe-crawler) signs its outbound calls to
  the User service + catalogues with an HMAC header
  `X-Internal-Auth: t=<ms>,u=<actingUser|->,s=<HMAC-SHA256(secret, METHOD\npath\nu\nt)>`
  (`internal/InternalAuth`, a per-service copy; 5-minute replay window;
  constant-time compare).
- **C1 fixed** — `User/UserInternalAuthFilter`: every `/api/**` call needs a
  valid header, and for `/api/accounts/{id}` (+ everything under it) the acting
  user must equal `{id}` → **403** otherwise. `POST /api/accounts`,
  `POST /api/authenticate`, `/api/restrictions` need the header but no
  acting-user match. Verified: raw `GET/DELETE /api/accounts/1` → 403.
- **C2 fixed** — `catalogue-common/InternalAuthFilter` (both catalogues) +
  `recipe-crawler/InternalAuthFilter`: a write (`POST/PUT/DELETE/PATCH`) under
  `/api/**` needs a valid header → **403** otherwise. Reads stay open (intended).
- The acting user is the BFF's `BffPrincipal` (from `SecurityContextHolder`) or,
  in the UI, the session's `CurrentUser` (via `ActingUser` ThreadLocal +
  `ActingUserFilter`). `INTERNAL_AUTH_SECRET` env, shared; `internal-auth.enabled`
  toggles the filters (tests set it false).
- Verified end-to-end in the stack: register / login / browse / create an
  ingredient / edit own profile / composed recipe / URL import all still work;
  every unauthenticated direct call to a guarded endpoint is 403.

**2026-09-09 — Phase 5, batch 1 (mechanical):**
- **H1 fixed** — root `compose.yaml`: every published port bound to `127.0.0.1`
  except the UI (`8081`). MySQL, the catalogues, the BFF, Zipkin/Prometheus/
  Grafana are no longer LAN-reachable.
- **H3 fixed** — Grafana anonymous role `Admin` → `Viewer`; login form
  re-enabled; admin creds via `GRAFANA_ADMIN_*` (default `admin`/`admin`).
- **M2 fixed** — `AccountServiceImpl.authenticate` runs one BCrypt check every
  time (real hash or a dummy) so an unknown user and a wrong password take the
  same time.
- **M6 fixed** — `catalogue-common/ApiExceptionHandler` maps
  `PropertyReferenceException` (bad `?sort=`) to **400**, not 500.
- **L2 partly fixed** — BFF calculators reject a non-finite `scale` and
  `servings < 1` with **400** (was `NaN`-through / silent clamp).

Still open *at the time* (**H4** and the module-local `compose.yaml`
localhost-binding were fixed on 2026-11-01 — see above): **H4** (DB creds →
untracked `.env`; the root `compose.yaml` is no longer version-controlled, but
per-service `compose.yaml` files still carry them), **M3** (register
enumeration), **M4** (no rate limiting), the module-local `compose.yaml` files
(not yet localhost-bound), `/actuator` unprotected, the low-severity L1–L7.

**Verdict:** the browser-facing edge (BFF `/bff/**`, Thymeleaf output escaping,
password handling, DTO boundaries) is genuinely well-built. The problem is the
**"trust the network" posture applied to services published on `0.0.0.0`** — the
User service and both catalogues have effectively no authentication, so anyone who
can reach the ports owns the data. Everything here is access-control and
hardening; nothing exotic.

---

## 🔴 Critical

### C1 — User service: no auth on any endpoint

> **FIXED 2026-09-09** — UserInternalAuthFilter: HMAC header required on every
> /api/**, acting user must match {id}. See the remediation log.

`User/SecurityConfig` is `anyRequest().permitAll()`. Account IDs are sequential.
Demonstrated unauthenticated against `:8084`:

| Action | Result |
|---|---|
| `GET /api/accounts/1` | full PII (username, email, displayName, timestamps, restrictions, favourites) |
| `PUT /api/accounts/1` `{"email":"attacker@evil.com"}` | 200 — email / display-name **takeover primitive** |
| `PUT /api/accounts/302/restrictions` | 200 — overwrote another user's **dietary-safety data** |
| `DELETE /api/accounts/402` | 204 — **delete any account** |
| `POST /api/accounts/1/favorites/recipes/353` | 200 |

`currentPassword` **is** verified on password change (401 on wrong) — that
control holds.

**Fix:** the BFF already authenticates the end user — forward a verified
identity (signed header / JWT) and have the User service enforce
`caller == {id}`. Interim: bind `:8084` to `127.0.0.1` and firewall it.

### C2 — IngredientCatalogue & RecipeCatalogue: no security at all

> **FIXED 2026-09-09** — InternalAuthFilter: a valid HMAC `X-Internal-Auth`
> header is required on writes under `/api/**`. Reads stay open. See the
> remediation log.

No `spring-security` on the classpath; every `POST`/`PUT`/`DELETE` on
`/api/ingredients`, `/api/recipes`, `/api/tags`, `/api/nutrition-reference` is
open. Demonstrated create + delete of ingredients / recipes / tags
unauthenticated. Anyone on the network can wipe or poison the catalogues.

**Fix:** shared filter requiring a BFF-forwarded identity for writes; reads may
stay open if intended.

---

## 🟠 High

### H1 — Every port published to `0.0.0.0`
`compose.yaml` uses `'8082:8082'` etc., so all ten services (incl. MySQL
`3306`) are LAN-reachable, not localhost-only. This is what makes C1/C2
exploitable in practice. **Fix:** `'127.0.0.1:8082:8082'` for everything except
the intended entry point.

### H2 — CSRF on the UI

> **FIXED 2026-09-09** — spring-boot-starter-security on the UI: CSRF tokens on
> form posts, SameSite=Strict cookie. See the remediation log (batch 3).

The UI has no Spring Security → no CSRF tokens, no Origin/Referer check, and
`JSESSIONID` is set with **no `SameSite` attribute**. Demonstrated with a forged
cross-site `POST` (bogus `Origin`, no token):
- `POST /account/restrictions` → replaced the victim's restrictions with `["csrf-injected"]`
- `POST /favourites/recipes/1`, `POST /settings/theme` → succeeded

Modern browsers' default `SameSite=Lax` blunts this for top-level POSTs, but
there is zero defense-in-depth and the impact touches **safety data**. **Fix:**
add `spring-boot-starter-security` to the UI (CSRF on by default for form
posts), or a `SameSite=Strict` + Origin-check filter.

### H3 — Grafana = anonymous Admin
`GF_AUTH_ANONYMOUS_ORG_ROLE=Admin`. Unauthenticated on `:3000`:
`GET /api/datasources` leaks internal topology; `/api/datasources/proxy/1/...`
proxies arbitrary queries through Grafana; `POST /api/serviceaccounts
{"role":"Admin"}` returned **201** (created + deleted during the test) →
persistent admin foothold. **Fix:** anonymous → `Viewer` or off; bind `:3000`
to localhost.

### H4 — MySQL exposed with weak, in-repo credentials

> **FIXED 2026-11-01** — credentials removed from every `compose.yaml`, from the
> `application.properties` defaults and from the docs; `${MYSQL_*:?set in .env}`
> makes Compose refuse to start without them; `.env` git-ignored, `.env.example`
> names only; the live passwords **rotated** (old ones rejected). See the
> remediation log.

`:3306` was open, and trivially guessable root and app passwords were hard-coded
in committed `compose.yaml` files (and as defaults in `application.properties`).
With the repository in hand, a `mysql -uroot -p<the committed value>` from the
host dumped all three schemas — PII + **BCrypt hashes for offline cracking**.
**Fix:** don't publish 3306 (done in H1); move creds to an untracked `.env`; use
non-trivial values — and, because the old ones were public, rotate them.

---

## 🟡 Medium

| # | Finding | Fix |
|---|---|---|
| M1 | **[FIXED 2026-09-09]** ~~Session fixation (UI)~~ — `JSESSIONID` not rotated on login; Tomcat also emits `;jsessionid=` URL rewriting. Logout *does* `invalidate()`. | `request.changeSessionId()` after auth; `server.servlet.session.tracking-modes=cookie`. |
| M2 | **[FIXED 2026-09-09]** ~~Username enumeration via login timing~~ — wrong password for a real user ≈ 377 ms vs unknown user ≈ 73 ms (BCrypt only runs when the user exists). Same 401 + body. | `AccountServiceImpl.authenticate` now runs one BCrypt check every time, against a real hash or a dummy, so both paths take the same time. |
| M3 | **Username/email enumeration via registration** — distinct `"username already taken"` vs `"email already registered"` from the User service. | Generic "check the form" message. |
| M4 | **No rate limiting** anywhere — `/login`, `/register`, `/authenticate`, CSV import, calculators. | Bucket/filter per IP + per account. |
| M5 | **[FIXED 2026-09-09]** ~~UI missing security headers~~ — no `X-Frame-Options` / `X-Content-Type-Options` / CSP / `Referrer-Policy`; catalogues send none. | Security starter, or a header filter. |
| M6 | **[FIXED 2026-09-09]** ~~`sort` param → 500~~ on the catalogues (`?sort=(select 1)`). *Not* SQLi — Spring Data validates the property before building SQL. It was an unhandled `PropertyReferenceException` = log-spam DoS + a signal. | `catalogue-common/ApiExceptionHandler` maps `PropertyReferenceException` to **400**. |
---

## 🟢 Low / informational

- **L1 CSV formula injection** — import stores `=`/`@`/`+`/`-`-prefixed values
  verbatim. Inert today (no CSV *export*); prefix with `'` if an export is added.
- **L2 Calculator robustness** — `portions?scale=NaN` → 200 with `NaN` in every
  field; `nutrition?servings=-5` → 200 (silently clamped to 1). Should be 400.
- **L3** `/actuator/info` leaks artifact name + version + build time.
  `/actuator/health` is correctly terse (`show-details=when-authorized`).
- **L4** `openapi.yaml` served publicly on the catalogues — intended, but a full
  API map for an attacker.
- **L5** Zipkin (`:9411`) and Prometheus (`:9412`) unauthenticated — trace /
  metric exposure. Prometheus admin API is off (good).
- **L6** Open-redirect guard on `?next=` allows protocol-relative `//host`;
  Spring normalised it safely here — still tighten to reject `//` and `\`.
- **L7** `spring.thymeleaf.cache=false` ships in the UI prod image;
  `AuthInterceptor` creates a session for every anonymous hit.

---

## Prompt-injection / AI-targeted content

**No LLM in the app today → no live prompt-injection sink.** Stored payloads
(`IGNORE ALL PREVIOUS INSTRUCTIONS… </system>`, tag `<|im_start|>system`) were
checked: returned as inert data in API JSON, **not** echoed to container logs,
**not** present in Zipkin span names/tags.

This is a **design constraint for Phase 4's RAG / webcrawler**, not a bug now:
recipe steps, ingredient names, and tags are fully attacker-controlled free text
(C2). Anything that later feeds them to an LLM must treat them as untrusted —
delimit clearly, never concatenate into the system/instruction context, don't
act on instructions found in stored content. The webcrawler has the same issue
for fetched page text.

---

## What is already solid (don't regress)

- **BFF `/bff/**`** — `accountId` from the session, ignores `?accountId=`;
  `/bff/accounts/{id}/favorite-recipes` has an explicit ownership check (403
  for another account).
- **Stored XSS: not exploitable** — Thymeleaf `th:text` escaping holds across
  list / detail / chips / steps / `<title>` and the error page's reflected
  `detail`; no `th:utext` on user data.
- **Passwords** — BCrypt via `DelegatingPasswordEncoder`; `currentPassword`
  enforced on change.
- **Mass assignment: not vulnerable** — smuggled `id` / `role` / `restrictions`
  on register are ignored.
- **SQLi: not found** — derived queries + Specifications use bound parameters;
  the `name` LIKE filter is safe (boolean-differential negative).
- **Multipart** — 5 MB cap (413); the importer parses in memory and never
  writes to disk, so a `../../etc/passwd` filename is inert. Containers run as
  non-root; `ddl-auto=validate`.

---

## Priority order

1. Bind all ports to `127.0.0.1` except the entry point (**H1** — one line,
   removes most exposure).
2. Inter-service auth: BFF forwards a verified identity; User service + catalogues
   enforce it (**C1, C2**).
3. `spring-boot-starter-security` on the UI for CSRF + headers; `SameSite` on
   the session cookie; rotate the session on login (**H2, M1, M5**).
4. Grafana anonymous → Viewer/off; DB creds out of tracked files (**H3, H4**).
5. Dummy-hash on unknown user; `PropertyReferenceException` → 400 (**M2, M6**).
