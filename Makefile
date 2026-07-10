# SalesPipe developer shortcuts. See README "Getting started" for the full flow.
.PHONY: up down seed-model logs-scoring

# Bring the whole stack up (frontend on :3001, app on :8080, mlflow on :5000).
# The model-seed job trains + promotes the lead-scoring model automatically before the
# scoring service starts, so leads get real AI scores on a fresh bring-up.
up:
	docker compose up -d --build

down:
	docker compose down

# Manually (re)train + promote the lead-scoring model against the running MLflow, then
# reload the scoring service so it picks up the new Production model. Use after changing
# training code or if you skipped the automatic seed. Requires the stack (mlflow) to be up.
seed-model:
	docker compose run --rm model-seed
	docker compose restart lead-scoring

logs-scoring:
	docker compose logs -f lead-scoring
