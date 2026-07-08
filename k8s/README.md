# Local K8s (kind)

```bash
kind create cluster --name salespipe
docker build -t salespipe:local .
kind load docker-image salespipe:local --name salespipe
kubectl apply -f k8s/
kubectl -n salespipe rollout status deploy/salespipe --timeout=180s
kubectl -n salespipe port-forward svc/salespipe 8080:80
```

Managed Postgres/Redis: drop `postgres.yaml`/`redis.yaml`, point `salespipe-config`/`secret`
at the managed endpoints. StatefulSet+PVC is the local CORE path only.

## Known gotcha: `REDIS_PORT`

Kubernetes auto-populates Docker-links-style discovery env vars for every Service in a
namespace, so any pod started after the `redis` Service exists gets `REDIS_PORT=tcp://<clusterIP>:6379`
injected automatically. That collides with the app's own `${REDIS_PORT:6379}` placeholder in
`application.yml` and breaks int parsing at startup (`NumberFormatException` on the Redis config
bind). `k8s/deployment.yaml` pins `REDIS_PORT=6379` explicitly via `env:` to shadow the
injected value — keep that entry if you copy this manifest elsewhere.
