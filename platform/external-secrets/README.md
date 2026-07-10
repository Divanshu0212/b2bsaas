# External Secrets Operator (T5.5)

Install the operator, then apply the ClusterSecretStore:

```bash
helm repo add external-secrets https://charts.external-secrets.io
helm install external-secrets external-secrets/external-secrets \
  -n external-secrets --create-namespace
kubectl apply -f platform/external-secrets/cluster-secret-store.yaml
```

Deploy the app with `secret.mode=eso` (staging/prod overlays already set this). ESO then
syncs `DB_PASSWORD` / `JWT_SECRET` from Vault paths `salespipe/db` + `salespipe/jwt` into a
native K8s Secret the Deployment consumes. No secret value is ever in git or a ConfigMap.

**Fallback (CORE):** leave `secret.mode=plain` — the chart renders a plain K8s Secret from
values (local/kind only). This is the documented gap: material sits in the values file, so
plain mode is dev-only.
