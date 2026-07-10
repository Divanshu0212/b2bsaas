# GitOps (Argo CD) — T5.4

Argo CD watches this directory as the **declarative desired state** for the cluster.
CI never runs `kubectl apply` / `helm upgrade`; it only pushes an image-tag bump into
`apps/*-staging.values.yaml`, and Argo reconciles the diff.

## Layout

```
gitops/
  apps/
    salespipe-staging.values.yaml      # staging overlay; image.tag bumped by CI
    salespipe-prod.values.yaml         # prod overlay; tag promoted manually
    lead-scoring-staging.values.yaml
    lead-scoring-prod.values.yaml
  argocd/
    project.yaml                       # AppProject scoping repos/destinations
    salespipe-staging.yaml             # Application, syncPolicy: automated
    salespipe-prod.yaml                # Application, syncPolicy: manual (promote)
    lead-scoring-staging.yaml
    lead-scoring-prod.yaml
```

## Flow

1. Merge to `main` → CI builds SHA-tagged images, pushes to GHCR.
2. CI rewrites `image.tag` in the two `*-staging.values.yaml` and commits.
3. Argo auto-syncs the staging Applications → rolling zero-downtime deploy.
4. **Prod promote:** copy the verified staging tag into `*-prod.values.yaml`, commit;
   the prod Applications are `syncPolicy: manual`, so a human syncs them in the Argo UI/CLI.

## Fallback (no Argo)

If Argo isn't installed, deploy the same charts directly:

```bash
helm upgrade --install salespipe ./charts/salespipe \
  -n salespipe -f ./charts/salespipe/values-staging.yaml \
  --set image.tag=<sha>
```
