# CLAUDE.md — SalesPipe / b2bsaas

Guidance for AI agents working in this repository.

## Git commit rules (IMPORTANT)

- Author and committer for **every** commit here:
  - name: `divanshu0212`
  - email: `divanshu0212@gmail.com`
  - (already set in repo-local `.git/config`)
- **Do NOT add a `Co-Authored-By: Claude` trailer** (or any `noreply@anthropic.com`) to commit messages. The repo must read as the user's sole work. Adding it makes `claude` appear in the GitHub contributors graph, which is not wanted.
- Remote: `origin` → https://github.com/Divanshu0212/b2bsaas (public).

## Project

SalesPipe — a multi-tenant B2B Sales CRM built as a modular monolith on Kubernetes. See [`README.md`](README.md) for the full overview and [`docs/plan/`](docs/plan/) for the phase-by-phase implementation plan (start with [`docs/plan/00-overview.md`](docs/plan/00-overview.md)).

## Working conventions

- The plan is authoritative: build phase by phase, do not start a phase before the prior phase's CORE tasks pass their acceptance tests.
- When code exists: add a `.gitignore` (Java/Gradle + Python) before the first code commit.
- Follow the improvements/decisions recorded in `docs/plan/00-overview.md` (§1 change log, §7 decision log) — they intentionally diverge from the original SDD.
