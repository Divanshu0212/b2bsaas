# Phase 6 — Frontend (Next.js)

**Goal:** A real web client for SalesPipe — Next.js app consuming the API built in Phases 1–5. Kanban board, lead/deal detail with live AI score + SHAP factors, activity timeline, notifications, auth, reporting dashboard.

**Demo at end:** Log in through the browser, see the Kanban board grouped by stage, drag a deal card between stages (optimistic UI + conflict handling on 409), open a lead to see its timeline, current score, score history chart, and top SHAP factors, see in-app notifications update, view the funnel report.

**Prerequisites:** Phases 1–4 CORE passing (needs auth, pipeline, timeline, scoring APIs live). Phase 5 not required — frontend can be built and deployed against a Phase-1-4 backend; it gets its own Helm chart/Ingress path in this phase's platform tasks.

---

## Tasks

### T6.1 — Project scaffold
- Next.js 15 (App Router), TypeScript, React Server Components where they help (read-heavy pages), Client Components for interactive board/forms.
- Styling: Tailwind CSS + a component primitive layer (shadcn/ui) — fast, consistent, no hand-rolled design system.
- State/data: TanStack Query for server-state caching/invalidation; Zustand (or React context) only for local UI state (drag state, modals).
- **Files:** `frontend/` (own top-level dir, sibling to backend `com.salespipe` code and `ml/`), `frontend/package.json`, `next.config.ts`, `tailwind.config.ts`.
- **Accept:** `npm run dev` serves a starter page; TypeScript strict mode on, no `any` in scaffold.

### T6.2 — Typed API client from OpenAPI
- Generate a typed client from the springdoc-openapi spec (`openapi-typescript` or `orval`) — request/response types match the backend contract exactly, regenerated on backend contract changes.
- Thin fetch wrapper: attaches access token, retries once through `/auth/refresh` on 401, redirects to login on refresh failure.
- **Files:** `frontend/src/lib/api/generated/*` (generated, gitignored or committed — decide per CI setup), `frontend/src/lib/api/client.ts`.
- **Accept:** a generated function call against a running backend returns correctly typed data; an expired access token triggers one silent refresh-and-retry.

### T6.3 — Auth flow
- Login page → calls `/auth/login`, stores access token in memory (not localStorage — XSS surface), refresh token handled as an **httpOnly cookie** set by the backend (requires a small backend adjustment: refresh endpoint sets/reads the cookie instead of returning the refresh token in the JSON body — note this as a Phase-1/2 backend touch-up, call it out explicitly to the backend team/self).
- Route protection via Next middleware — unauthenticated requests to app routes redirect to `/login`.
- Logout calls `/auth/logout` (revokes the refresh family per Phase 1).
- **Files:** `frontend/src/app/login/page.tsx`, `frontend/middleware.ts`, `frontend/src/lib/auth/*`.
- **Accept:** login → protected page loads; manually expiring the access token still works via refresh; logout invalidates the session (retrying a protected call 401s).

### T6.4 — App shell & navigation
- Layout: sidebar nav (Pipeline, Leads, Reports, Notifications), top bar (org name, user menu, notification bell).
- RBAC-aware nav: hide/disable admin-only sections for `SALES_REP`.
- **Files:** `frontend/src/app/(app)/layout.tsx`, `frontend/src/components/nav/*`.
- **Accept:** nav renders per role; ADMIN-only links absent for a SALES_REP-logged-in session.

### T6.5 — Kanban pipeline board
- `GET /deals/pipeline` renders columns = `deal_stages` (org-configurable, ordered by `position`), cards = deals.
- Drag-and-drop (dnd-kit) triggers `PATCH /deals/{id}/stage` **optimistically** (card moves immediately) with rollback-on-error.
- **Conflict handling**: a 409 (optimistic-lock version mismatch from a concurrent drag) rolls the card back to its server-confirmed column and shows a toast — this is the UI-side half of the backend's `@Version` story, worth calling out together in an interview.
- Card shows: lead name, account, amount, owner avatar, current AI score badge (color-coded by threshold).
- **Files:** `frontend/src/app/(app)/pipeline/page.tsx`, `frontend/src/components/kanban/*`.
- **Accept:** drag persists on reload; two browser tabs dragging the same card concurrently — one wins, the other rolls back with a visible conflict message.

### T6.6 — Lead list & detail
- Lead list: paginated/filterable table (`GET /leads?status=&owner=&page=`), server-driven pagination via TanStack Query.
- Lead detail page:
  - Header: contact/account info, owner, status.
  - **Score panel**: current score (`GET /leads/{id}/score`), sparkline of score history, **top SHAP factors** rendered as a small horizontal bar list ("why is this lead hot").
  - **Timeline**: merged activity feed (`GET /leads/{id}/timeline`), infinite-scroll, icon-per-activity-type.
  - Manual "refresh score" action hitting the cache-miss/manual sync path (Phase 3 §T3.4) — shows a loading state, not a blocking spinner over the whole page.
- **Files:** `frontend/src/app/(app)/leads/page.tsx`, `frontend/src/app/(app)/leads/[id]/page.tsx`, `frontend/src/components/leads/ScorePanel.tsx`, `TimelineFeed.tsx`.
- **Accept:** score panel renders history + factors from real API data; timeline paginates without duplicate/missing entries across pages.

### T6.7 — Notifications
- Bell icon with unread count, dropdown list (`GET /notifications` — endpoint added here if not already present from Phase 2/4 notification work; reuse if it exists).
- Polling (short interval) for new notifications in this phase; **WebSocket/SSE live push is the STRETCH upgrade** (matches SDD stretch goal — live Kanban/notifications over a socket).
- Mark-as-read on open.
- **Files:** `frontend/src/components/notifications/*`.
- **Accept:** a backend-triggered notification (e.g., hot-lead threshold) appears in the dropdown within one polling interval; opening marks it read and persists across reload.

### T6.8 — Reporting dashboard
- `GET /reports/funnel` rendered as a funnel chart + a simple rep-leaderboard table.
- **Files:** `frontend/src/app/(app)/reports/page.tsx`.
- **Accept:** funnel numbers match a manual query against the same data.

### T6.9 — Forms: lead/account/contact/deal CRUD
- Create/edit forms for accounts, contacts, leads, deals using a schema-validated form lib (React Hook Form + Zod), Zod schema mirroring the backend Bean Validation rules.
- Optimistic-locking awareness on deal edit (send `version`, surface 409 as "someone else updated this, reload").
- **Files:** `frontend/src/components/forms/*`.
- **Accept:** invalid input blocked client-side with the same rules the backend enforces; a stale-version submit shows the conflict message instead of a generic error.

### T6.10 — Error handling & loading states
- Global error boundary + toast system for API errors (RFC 7807 problem+json from the backend, parsed into a friendly message).
- Skeleton loaders for board/list/detail (no layout shift).
- **Files:** `frontend/src/components/ui/ErrorBoundary.tsx`, skeleton components.
- **Accept:** killing the backend mid-session shows a clear "can't reach server" state, not a blank page or unhandled exception.

### T6.11 — Testing
- Component tests (Vitest + React Testing Library) for Kanban drag logic, score panel rendering, form validation.
- E2E (Playwright): login → view pipeline → drag a deal → view lead detail → see score. Run against a Testcontainers-backed backend in CI (same infra discipline as the Java side).
- **Files:** `frontend/tests/*`, `frontend/e2e/*.spec.ts`.
- **Accept:** E2E suite green in CI against a real (containerized) backend, not a mock server.

### T6.12 — Containerization & deploy
- Multi-stage Dockerfile (Next.js standalone output) — same discipline as the backend's multi-stage build.
- Add to `docker-compose.yml` for local full-stack dev.
- K8s: own Deployment + Service + Ingress path (`/` → frontend, `/api` → backend), or same Ingress with path-based routing. If Phase 5 (Helm/KEDA/Argo) already exists, add a `charts/frontend/` chart following the same pattern as `charts/salespipe/`.
- **Accept:** `docker compose up` serves the full stack (frontend + backend + infra) on one command; K8s Ingress correctly routes `/` and `/api/*` to the right Service.

---

## Testing requirements (Phase 6)
- **Unit/component** (Vitest + RTL): drag-and-drop reducer logic, score/SHAP rendering, form Zod validation.
- **E2E** (Playwright): full golden path against a real backend (Testcontainers or a docker-compose stack in CI).
- **Contract**: generated API client type-checks against the current OpenAPI spec in CI — fails the build if frontend and backend contracts drift.
- **Accessibility**: basic axe-core pass on core pages (board, lead detail, forms).

## New/adjusted backend touch-points
- Refresh-token delivery via httpOnly cookie instead of JSON body (small adjustment to Phase 1's `/auth/refresh` — call out and apply retroactively).
- `GET /notifications` (list + mark-read) if not already exposed by Phase 2/4 notification work.
- CORS configuration on the backend for the frontend's origin (dev + prod).

## Tier labels
- CORE: scaffold, typed API client, auth flow, app shell, Kanban board with conflict handling, lead list/detail with score+timeline, forms, error handling, component + E2E tests, containerization.
- STRETCH: WebSocket/SSE live push (notifications + board), full accessibility audit beyond the basic pass, dark mode / theming polish.

## Interview talking points
- Optimistic UI + server-authoritative conflict resolution — the frontend half of the backend's optimistic-locking story, told as one end-to-end narrative (drag → 409 → rollback → toast).
- Typed client generated from the OpenAPI contract — frontend/backend drift becomes a CI failure, not a runtime surprise.
- SHAP factors surfaced in the UI turn "trust me, it's ML" into an explainable, reviewable signal for sales reps.
- Access token in memory + refresh in httpOnly cookie — deliberate XSS-surface reduction, not the naive localStorage approach.
- E2E tests run against real containerized infra, matching the backend's Testcontainers philosophy instead of mocking the API away.

## Risks / gotchas
- Storing the refresh token as an httpOnly cookie requires a CORS + `SameSite` decision (`SameSite=Strict` breaks cross-subdomain deploys; `Lax` is usually right here) — get this wrong and refresh silently fails in prod only.
- Optimistic drag-and-drop without careful rollback UX reads as "buggy" rather than "resilient" — the conflict toast must be immediate and specific ("Alex moved this to Proposal"), not a generic error.
- Polling notifications too aggressively re-creates the exact request-thread pressure the backend's async design was built to avoid — keep the interval sane (e.g. 15–30s) until/unless the WebSocket stretch lands.
- Generated API types drift silently if regeneration isn't wired into CI — make the contract check (see Testing) a required check, not a manual step.
