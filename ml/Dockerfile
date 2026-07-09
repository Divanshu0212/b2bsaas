# Lead scoring service (phase-3 T3.2). Embedding model in memory is heavy -> the pod
# needs bigger resource limits (set in Phase 5 manifests, per phase-3 "Risks/gotchas").
FROM python:3.12-slim AS base

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    HF_HOME=/models/hf

WORKDIR /app

# System deps for xgboost/shap (libgomp) kept minimal.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgomp1 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
COPY training ./training

EXPOSE 8000
# uvicorn single worker: the model is loaded once at startup into process memory.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
