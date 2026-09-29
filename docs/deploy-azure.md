# Deploying to Azure Container Apps

These are notes and commands for deploying the service; they have not been run against a real subscription.
Names in `UPPER_CASE` are placeholders.

## Target architecture

```
GitHub Actions --(OIDC, no stored secrets)--> Azure
                                              |
   ACR (image) --> Container Apps (ledger-service, 1..3 replicas, external HTTPS ingress)
                                              |  private connection
                                   Azure Database for PostgreSQL Flexible Server
```

Why Container Apps: it runs the existing Docker image unchanged, gives HTTPS ingress, revisions and
autoscaling without operating Kubernetes, and pairs well with Postgres Flexible Server.

## 1. Provision (one-off)

```bash
RG=ledger-rg
LOC=westeurope
ACR=ledgeracr$RANDOM           # must be globally unique
ENV=ledger-env
APP=ledger-service
PG=ledger-pg-$RANDOM

az group create -n $RG -l $LOC

# Container registry
az acr create -g $RG -n $ACR --sku Basic

# PostgreSQL Flexible Server (burstable tier is enough for a demo)
az postgres flexible-server create -g $RG -n $PG -l $LOC \
  --tier Burstable --sku-name Standard_B1ms --version 16 \
  --admin-user ledgeradmin --admin-password "$PG_ADMIN_PASSWORD" \
  --database-name ledger --public-access None
# For production put the server and the Container Apps environment in the same VNet and use a private
# endpoint. For a demo, allow Azure services:  az postgres flexible-server firewall-rule create ... 0.0.0.0

# Container Apps environment
az containerapp env create -g $RG -n $ENV -l $LOC
```

## 2. Build and push the image

```bash
az acr login -n $ACR
docker build -t $ACR.azurecr.io/ledger-service:$(git rev-parse --short HEAD) .
docker push $ACR.azurecr.io/ledger-service:$(git rev-parse --short HEAD)
```

## 3. Create the app

Secrets are stored as Container Apps secrets and surfaced as environment variables; nothing sensitive goes in
the image or in the repo.

```bash
az containerapp create -g $RG -n $APP --environment $ENV \
  --image $ACR.azurecr.io/ledger-service:TAG \
  --registry-server $ACR.azurecr.io --registry-identity system \
  --target-port 8080 --ingress external \
  --min-replicas 1 --max-replicas 3 \
  --cpu 0.5 --memory 1Gi \
  --secrets db-password="$PG_APP_PASSWORD" bootstrap-key="$LEDGER_BOOTSTRAP_API_KEY" \
  --env-vars \
    DB_URL="jdbc:postgresql://$PG.postgres.database.azure.com:5432/ledger?sslmode=require" \
    DB_USER=ledgeradmin \
    DB_PASSWORD=secretref:db-password \
    LEDGER_BOOTSTRAP_API_KEY=secretref:bootstrap-key
```

`--registry-identity system` gives the app a managed identity; grant it pull rights:

```bash
PRINCIPAL=$(az containerapp show -g $RG -n $APP --query identity.principalId -o tsv)
az role assignment create --assignee $PRINCIPAL --role AcrPull \
  --scope $(az acr show -n $ACR --query id -o tsv)
```

Health probes (the app exposes `GET /health`, which checks the database):

```bash
az containerapp update -g $RG -n $APP --yaml probes.yaml
```

```yaml
# probes.yaml (fragment)
properties:
  template:
    containers:
      - name: ledger-service
        probes:
          - type: Liveness
            httpGet: { path: /health, port: 8080 }
            periodSeconds: 15
          - type: Readiness
            httpGet: { path: /health, port: 8080 }
            initialDelaySeconds: 20
            periodSeconds: 10
```

## 4. Continuous deployment from GitHub Actions

Use OpenID Connect so the workflow holds no long-lived Azure credentials: create an app registration with a
federated credential for `repo:OWNER/REPO:ref:refs/heads/main`, grant it `Contributor` on the resource group
and `AcrPush` on the registry, then store `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID` as
repository variables.

```yaml
# .github/workflows/deploy.yml (not included in the repo; add once Azure exists)
on:
  workflow_run:
    workflows: [CI]
    types: [completed]
    branches: [main]
permissions:
  id-token: write
  contents: read
jobs:
  deploy:
    if: github.event.workflow_run.conclusion == 'success'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: azure/login@v2
        with:
          client-id: ${{ vars.AZURE_CLIENT_ID }}
          tenant-id: ${{ vars.AZURE_TENANT_ID }}
          subscription-id: ${{ vars.AZURE_SUBSCRIPTION_ID }}
      - run: |
          az acr build -r $ACR -t ledger-service:${{ github.sha }} .
          az containerapp update -g $RG -n $APP --image $ACR.azurecr.io/ledger-service:${{ github.sha }}
```

## Things that matter for this specific service

| Concern | What to do |
| --- | --- |
| **Migrations** | Flyway runs at startup. With several replicas starting together, Flyway takes a database lock, so one applies the migration and the others wait. Keep migrations backward compatible with the previous image so rolling revisions never see a schema they cannot use. |
| **Nightly rollup job** | It is an in-process `@Scheduled` task. Keep `--min-replicas 1` (scale-to-zero would skip the run). With multiple replicas a Postgres advisory lock lets exactly one instance do the work. |
| **Rate limiting** | Buckets are in memory per replica, so the effective limit is up to `replicas x limit`. Fine for a demo; for a hard global limit move the counters to Redis or Postgres. |
| **API keys** | The bootstrap key comes from the `LEDGER_BOOTSTRAP_API_KEY` secret. Further keys are inserted as SHA-256 hashes into `api_keys`. Rotate by adding a new key, switching clients, then setting `active = false` on the old one. |
| **Database credentials** | The demo uses the admin user. For real use create a least-privilege role (DML only, no DDL) for the app and run migrations with a separate role. Better still, use Entra ID authentication with the managed identity. |
| **TLS** | Ingress terminates TLS. Keep `sslmode=require` on the JDBC URL. |
| **Backups** | Enable Flexible Server point-in-time restore; it is the only copy of the journal. |
