# Runtime checkpoint, 24 October 2026

The public site is https://digital-hustle.ru/. Caddy serves the frontend and proxies
`/api/*` to Gateway on loopback port 5001. All five Java services and both PostgreSQL
instances run in `/opt/cashflow/compose.yaml`. Database ports are not public.

This runtime uses Orchestrator public-contract PR #25 (`27a65c8`) plus persisted alerts/gap explanations from PR #26 and the Gateway/import
compatibility changes in this branch. The extra routes expose the public CSV upload, company
categories/counterparties and company/account archive through the existing JWT and
CSRF filters. No internal endpoints are added. PreProcessor must join the
orchestrator network and receive `PREPROCESSOR_ORCHESTRATOR_URL=http://orchestrator:8083`.
The full Compose example contains that URL; the running Compose file was aligned.

Verified locally: Orchestrator check/bootJar (128 tests, one optional external
PreProcessor fixture test skipped), Gateway check/bootJar (109 tests, no failures),
and PreProcessor check/bootJar (98 tests, no failures). Gateway tests exercise authenticated multipart forwarding,
unauthenticated rejection, identity stripping, and the new catalog/archive routes.

Verified through the public browser against real services: email code delivery,
verification, profile/session, company and account creation, and persistence after
page reload. A real two-row synthetic CSV was parsed, staged and finalized into
PostgreSQL (2 accepted, 0 rejected, 0 duplicate); the operations remained visible
after reload. PreProcessor accepts the new public ImportRs, obtains the staging
version from ETag, and preserves server counters/dataVersion. Without ETag it
refuses to guess a version. The stage request hash excludes volatile preparedAt.
Synthetic verification records are clearly named as a server check.
This does not certify all financial workflows or the numerical forecast.

Before migration, both database dumps, the running Compose file, three previous
JARs and Docker image tags were preserved under
`/opt/cashflow/backups/before-web-compat-20261024` (root only). Secrets remain in
server env files and are not in this repository. Do not delete volumes for recovery.
PR #25 migrates enum values and import metadata: rolling back the old Orchestrator
image alone is insufficient; a database rollback requires the protected backup and
an explicit recovery procedure.

The frontend release SHA and mock mode are available at `/deployment.json`.
Normal deployment has `backend:true` and `mockApi:false`; code 000123 is inactive.

## Remaining browser integration

Frontend PRs #27–#32 are merged. PR #31 adds the account link **Обзор и прогноз**
and the **Запустить прогноз** command. Its browser tests use contract fixtures;
they do not certify the live financial API.

The current Gateway does not expose the browser's company-scoped dashboard,
forecast creation/result and related financial URLs. Native Orchestrator routes
are mostly account-scoped. Dashboard payloads also differ: the browser expects
public permission fields, structured data quality and scoped balance/job DTOs.
The overview intentionally hides the forecast command when its dashboard fails.
Until those routes and projections agree with the hub contract, a live forecast
cannot be launched through the browser. The recurring-pattern and expected-payment
browser list URLs similarly returned 404 during the runtime check.

PR #32 adds **Качество**, the historical backtest screen. Its company-scoped
backtest list/create/result/origins URLs require the same public API work. Check
request semantics as well as routing: the native backtest command requires an
`asOfDate`, while the public client submits historical origin dates, window,
scope and account ID without that field. A URL rewrite alone is insufficient.

Keep the real-service configuration and financial validation. Fix the public
routes/DTO projections and verify an actual authenticated browser job through to
its saved result; do not replace missing financial results with client fixtures.
