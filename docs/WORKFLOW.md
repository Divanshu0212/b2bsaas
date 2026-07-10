# SalesPipe — Running & Using the App

End-to-end guide: how to start the stack, what every page does, where things live, and the
day-to-day dev workflow. For architecture and the phase plan see [`../README.md`](../README.md)
and [`plan/`](plan/).

---

## 1. Start everything (one command)

The whole stack — Postgres, Redis, Kafka + Schema Registry, Debezium, MLflow, the ML scoring
service, the Spring Boot backend, and the Next.js frontend, plus Prometheus/Grafana/Tempo/Loki
— runs from Docker Compose:

```bash
docker compose up -d --build
```

First build takes a few minutes (Gradle + Next production build). Watch it come up:

```bash
docker compose ps
docker compose logs -f app        # backend
docker compose logs -f frontend   # web client
```

### Where each thing listens

| Service | URL | What it is |
|---|---|---|
| **Frontend (use this)** | http://localhost:3001 | the web app |
| Backend API | http://localhost:8080 | REST API (Swagger at `/swagger-ui.html`) |
| ML scoring | http://localhost:8000 | FastAPI lead-scoring service |
| MLflow | http://localhost:5000 | model registry / experiments |
| Grafana | http://localhost:3000 | dashboards (admin/admin) |
| Prometheus | http://localhost:9090 | metrics |
| Postgres | localhost:5432 | `salespipe` / `salespipe` |

> The frontend is on **3001** because Grafana already owns 3000. The browser talks to the
> frontend only; the frontend proxies `/api/*` to the backend, so you never hit :8080 directly.

Stop it all: `docker compose down` (add `-v` to wipe volumes/data).

---

## 2. First login

1. Open **http://localhost:3001** → you're bounced to `/login`.
2. Click **Create a new workspace** (first run has no users).
3. Enter an org name + email + password (≥ 8 chars). This creates the org and makes you its
   **ADMIN**. Default pipeline stages are seeded automatically.
4. You land on the **Pipeline**.

The access token is kept **in memory** (gone on reload — the app silently re-mints it from an
httpOnly refresh cookie). The refresh token is never visible to JavaScript.

Additional users: registration always creates a *new org*. To add a `SALES_REP` to an existing
org, use the backend directly (no in-app "invite user" screen yet — see §5 gaps).

---

## 3. The pages — what to do where

Left sidebar is the map. Admin-only items (**Reports**, **Dead letters**) are hidden for
`SALES_REP`.

### Pipeline (`/pipeline`)
The Kanban board. Columns are your deal stages (in order); cards are deals showing the amount.
- **Drag a card** between columns to move the deal to a new stage. The move is *optimistic*
  (card jumps immediately). If someone else moved the same deal first, you get a conflict toast
  and the card rolls back — reload to see the current state.
- Column header shows deal count + total value.
- **New deal** (top right) → pick a stage, amount, currency, optionally link a lead.

### Leads (`/leads`)
Paginated, status-filterable table (New / Contacted / Qualified / Unqualified / Converted).
- **New lead** → set status, source, notes, optional account link.
- **Open** a lead → the detail page.

### Lead detail (`/leads/{id}`)
- **Score panel**: current AI score (0–100) with a Hot/Warm/Cold band, a sparkline of score
  history, and the **top SHAP factors** ("why is this lead hot"). **Refresh score** forces a
  sync recompute.
- **Timeline**: merged activity feed (lead created, emails opened/clicked, stage changes),
  "Load more" paginates.
- **Edit** (top right) → change status/source/notes. Concurrent edits surface a conflict message.

### Accounts (`/accounts`) & Contacts (`/contacts`)
CRM master data. List with pagination, **New** to create, trash icon to delete. Contacts can be
linked to an account.

### Reports (`/reports`) — ADMIN
- **Funnel**: deals per stage (in pipeline order) as bars; hover for count + value.
- **Rep leaderboard**: each owner's deal count and total value, ranked.

### Notifications (`/notifications`) + bell
Hot-lead and stage-change alerts. The **bell** in the top bar shows the unread count and polls
every 20s; the page is the full list. Click a notification to mark it read.

### Dead letters (`/admin/dlq`) — ADMIN
Operational tool: messages that failed Kafka processing after retries. Pick a DLQ topic, see the
failure reason + attempts, and **Replay** a message back to its origin topic.

---

## 4. Where things live (repo map)

```
src/main/java/com/salespipe/   Spring Boot backend, package-by-feature:
  identity/    auth, JWT, refresh tokens, CORS, security config
  crmcore/     leads, accounts, contacts
  pipeline/    deals, deal-stages, pipeline board, reports
  scoring/     score surfacing + recompute (calls the ML service)
  activity/    timeline
  notification/ notifications + consumers
  eventing/    outbox, idempotent consumers, DLQ admin
ml/                              Python FastAPI scoring service + training
frontend/                        Next.js web client
  src/app/(app)/                 authenticated pages (one folder per route)
  src/app/login/                 login/register
  src/components/                UI: nav/, kanban/, leads/, forms/, notifications/, ui/
  src/lib/api/                   endpoints.ts (typed calls), client.ts (fetch+auth), schema.d.ts
  src/lib/auth/                  in-memory token store, session decode
  src/lib/forms/schemas.ts       Zod validation mirroring backend rules
  src/proxy.ts                   route protection (Next 16 "proxy", ex-middleware)
charts/                          Helm: salespipe, lead-scoring, frontend
gitops/                          Argo CD apps + CI-bumped image tags
docs/plan/                       phase-by-phase implementation plan
```

### Adding things
- **New API endpoint**: add the controller under the right `com.salespipe.<module>.api`, then
  add a typed call in `frontend/src/lib/api/endpoints.ts` (+ types in `schema.d.ts`), and a page
  or component that uses it.
- **New page**: create `frontend/src/app/(app)/<route>/page.tsx`; add a nav entry in
  `frontend/src/components/nav/Sidebar.tsx` (set `roles: ["ADMIN"]` to gate it).
- **New form**: add a Zod schema in `src/lib/forms/schemas.ts` mirroring the backend's Bean
  Validation, then a form component using React Hook Form + `zodResolver`.

---

## 5. Local development (without full Docker)

Run pieces natively for fast iteration:

```bash
# backend (needs Postgres/Redis/Kafka — start just those with compose)
docker compose up -d postgres redis kafka schema-registry
./gradlew bootRun

# ML service
cd ml && pip install -r requirements.txt && uvicorn app.main:app --reload

# frontend (points at the backend on :8080 via the /api proxy)
cd frontend && npm install && npm run dev      # http://localhost:3000
```

Frontend dev commands (`cd frontend`):

| Command | Does |
|---|---|
| `npm run dev` | dev server (:3000) |
| `npm run typecheck` | `tsc --noEmit` |
| `npm run test` | Vitest component/unit tests |
| `npm run e2e` | Playwright golden-path (needs a running stack) |
| `npm run build` | production build |
| `npm run gen:api` | regenerate the typed API client from a running backend's OpenAPI spec |

Backend: `./gradlew check` runs unit + Testcontainers integration tests + the coverage gate.

### API contract
`frontend/src/lib/api/schema.d.ts` is the typed contract. It's committed and hand-maintained to
match the controllers; run `npm run gen:api` against a live backend to regenerate it from
`/v3/api-docs`. CI is the drift guard.

---

## 6. Known gaps (intentional)

- **No in-app user invite / org management UI** — registration creates a new org; adding a
  `SALES_REP` to an existing org is a backend operation.
- **Tenant hard-delete** (`DELETE /admin/tenants/{orgId}`) is destructive and deliberately left
  to the API/CLI, not the UI.
- **Live push** (WebSocket/SSE) is a documented stretch; notifications and the board use polling.
- Owner/lead names on some tables show IDs where the backend doesn't yet join the display name.

---

## 7. Quick troubleshooting

| Symptom | Likely cause |
|---|---|
| Login "works" then immediately bounces back | refresh cookie dropped — over plain http the backend must run with `APP_AUTH_COOKIE_SECURE=false` (compose sets this) |
| CORS error in the browser console | frontend origin not in `CORS_ALLOWED_ORIGINS` (compose allows `http://localhost:3001`) |
| Score panel empty | the lead has never been scored — hit **Refresh score**, or the ML service isn't up |
| Board won't load | backend or Postgres not ready; `docker compose logs app` |
| Reports/Dead-letters missing from nav | you're logged in as `SALES_REP` (admin-only) |
```
