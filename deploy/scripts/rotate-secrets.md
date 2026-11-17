# Rotating secrets

All secrets live in `deploy/env/<env>.env` (chmod 600, git-ignored) and in
`deploy/secrets/` (service-account JSON files). Nothing is baked into images.
Rotate on a schedule (recommended: SECRET_KEY and DB password yearly, immediately
after any suspected exposure or when an administrator with server access leaves).

| Secret | Effect of rotation | Procedure |
|--------|--------------------|-----------|
| `SECRET_KEY` (JWT HS256) | Every access token becomes invalid immediately; refresh tokens are opaque DB records and are **not** affected, so phones silently obtain a new access token on their next refresh. Dashboard users are logged out. | 1. `openssl rand -hex 32` 2. edit `SECRET_KEY` in the env file 3. `docker compose ... up -d api` (recreates the container). No downtime beyond the restart. |
| RS256 key pair (`JWT_PRIVATE_KEY_PATH` / `JWT_PUBLIC_KEY_PATH`) | Same as above. | Generate: `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out jwt-private.pem && openssl pkey -in jwt-private.pem -pubout -out jwt-public.pem`; place under `deploy/secrets/`, restart api. |
| `POSTGRES_PASSWORD` | DB connections fail until the API is restarted with the new value. | 1. `docker compose exec db psql -U $POSTGRES_USER -d postgres -c "ALTER USER $POSTGRES_USER PASSWORD '<new>';"` 2. update `POSTGRES_PASSWORD` in the env file 3. `docker compose ... up -d api backup`. Managed DB: change it in the provider console first, then the env file. |
| `METRICS_TOKEN` | Prometheus scrapes fail until its config is updated. | Update env file and `deploy/monitoring/prometheus.yml` (bearer token), restart api and Prometheus. |
| SMTP password | Password-reset mails fail until restarted. | Rotate at the mail provider, update env file, restart api. |
| Firebase service account (`FCM_SERVICE_ACCOUNT_FILE`) | Push stops until the new key is in place; the app keeps working by polling. | Google Cloud console > IAM > Service accounts > Keys: create a new key, replace `deploy/secrets/fcm-service-account.json`, restart api, then **delete the old key** in the console. |
| Play Integrity service account | Integrity verdicts return `not_configured` until replaced. | Same as above with `play-integrity-service-account.json`. |
| Android release keystore | **Cannot be rotated freely.** If you use Play App Signing, Google holds the app signing key and you can request an upload-key reset; if you sign locally, losing the key means a new package name. | See `docs/RELEASE.md` §Signing. Keep the keystore and its passwords in the company password manager plus an offline copy. |
| Administrator passwords | — | Dashboard > Employees > Reset password (forces change on next login). Users: Profile > Change password. |

After any rotation:

1. `deploy/scripts/healthcheck.sh https://attendance.<domain>` must pass.
2. Log in to the dashboard and to the app on one phone.
3. Record the rotation (date, who, which secret — not the value) in the operations log.
4. Revoke the old value at its source (cloud console, mail provider).

## Compromise response

If a secret may have leaked: rotate it immediately following the table above, then
force logout of all sessions by rotating `SECRET_KEY` **and** revoking refresh tokens
(`docker compose exec api python -m app.cli revoke-all-sessions` if provided by the
backend; otherwise `UPDATE refresh_tokens SET revoked_at = now() WHERE revoked_at IS NULL;`
via psql), review `audit_logs` for the exposure window, and follow
`docs/PRIVACY-POPIA.md` §Breach response if personal information may be affected.
