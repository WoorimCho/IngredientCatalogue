# Security assessment — Allergen Information System

Hands-on assessment against the local Docker stack (all services + infra) on
**2026-09-08**, plus source review. All test records created during testing were
deleted afterwards. This file covers the **whole system**, like `CHANGES.md`.

## Remediation log

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

Still open: **H4** (DB creds → untracked `.env`; the root `compose.yaml` is no
longer version-controlled, but per-service `compose.yaml` files still carry
them), **M3** (register enumeration), **M4** (no rate limiting), the module-local
`compose.yaml` files (not yet localhost-bound), `/actuator` unprotected, the
low-severity L1–L7.

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
`:3306` open; `root`/`verysecret`, `myuser`/`secret` are hard-coded in committed
`compose.yaml` files. `mysql -uroot -pverysecret` from the host dumps all three
schemas — PII + **BCrypt hashes for offline cracking**. **Fix:** don't publish
3306; move creds to an untracked `.env`; use non-trivial values.

---

## 🟡 Medium

| # | Finding | Fix |
|---|---|---|
| M1 | **[FIXED 2026-09-09]** ~~Session fixation (UI)~~ — `JSESSIONID` not rotated on login; Tomcat also emits `;jsessionid=` URL rewriting. Logout *does* `invalidate()`. | `request.changeSessionId()` after auth; `server.servlet.session.tracking-modes=cookie`. |
| M2 | **Username enumeration via login timing** — wrong password for a real user ≈ 377 ms vs unknown user ≈ 73 ms (BCrypt only runs when the user exists). Same 401 + body. | Always verify against a fixed dummy hash. |
| M3 | **Username/email enumeration via registration** — distinct `"username already taken"` vs `"email already registered"` from the User service. | Generic "check the form" message. |
| M4 | **No rate limiting** anywhere — `/login`, `/register`, `/authenticate`, CSV import, calculators. | Bucket/filter per IP + per account. |
| M5 | **[FIXED 2026-09-09]** ~~UI missing security headers~~ — no `X-Frame-Options` / `X-Content-Type-Options` / CSP / `Referrer-Policy`; catalogues send none. | Security starter, or a header filter. |
| M6 | **`sort` param → 500** on the catalogues (`?sort=(select 1)`). *Not* SQLi — Spring Data validates the property before building SQL. It is an unhandled `PropertyReferenceException` = log-spam DoS + a signal. | Map it to 400 in the exception handler. |

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
