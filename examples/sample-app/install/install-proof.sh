#!/usr/bin/env bash
# The install proof (P047): installs the sample app step by step as the install guides tell a customer's IT, on Linux or
# Windows (Git Bash), without Docker. Needs: the built jars, PostgreSQL's client programs and server service, vault, jq, curl.
#   APP_JAR, MOCKS_JAR   the built jars
#   PGHOST, PGPORT, PGUSER, PGPASSWORD   the database administrator
#   DB_STOP, DB_START    commands that stop and start the database service
# Every value here is made up for the proof. Passwords are random per run.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
work="$(pwd)/install-proof-work"
rm -rf "$work"; mkdir -p "$work/erase-log" "$work/export" "$work/vault-data"; cd "$work"
# Paths as Java and Vault on Windows read them.
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }
step() { echo; echo "=== $*"; }
fail() { echo "PROOF FAILED: $*" >&2; grep -vE '^[[:space:]]*(at |...)' app.log 2>/dev/null | tail -40 | cut -c1-600 || true; exit 1; }
random() { od -An -N16 -tx1 /dev/urandom | tr -d ' \n'; }

pids=""
cleanup() { for p in $pids; do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

owner_password="ow$(random)"; runtime_password="rt$(random)"; monitoring_password="mo$(random)"
mail_password="sample-mail-password"; api_key="sample-api-key"

step "1. Vault: server, Transit, KV, AppRole (docs/install/crypto.md section 1, docs/design/crypto.md section 7)"
cat > vault.hcl <<EOF
storage "file" { path = "$(native "$work/vault-data")" }
listener "tcp" {
  address     = "127.0.0.1:8200"
  tls_disable = 1
}
disable_mlock = true
EOF
vault server -config=vault.hcl > vault.log 2>&1 & pids="$pids $!"
export VAULT_ADDR=http://127.0.0.1:8200
for _ in $(seq 1 30); do curl -s "$VAULT_ADDR/v1/sys/health" >/dev/null && break; sleep 1; done
vault operator init -key-shares=1 -key-threshold=1 -format=json > init.json
vault operator unseal "$(jq -r '.unseal_keys_b64[0]' init.json)" >/dev/null
VAULT_TOKEN="$(jq -r .root_token init.json)"; export VAULT_TOKEN
vault secrets enable transit; vault secrets enable -path=secret -version=2 kv; vault auth enable approle
# The policies, copied from the install guide: the block between the ```hcl lines, split at the policy names.
sed -n '/```hcl/,/```/p' "$repo/docs/install/crypto.md" | grep -v '```' | sed 's/^  //; s/\r$//' > policies.txt
for p in platform-crypto-app platform-crypto-offboard platform-crypto-setup; do
  awk -v p="# $p" '/^# /{on=($0==p)} on' policies.txt > "$p.hcl"
  [ -s "$p.hcl" ] || fail "the guide has no policy $p"
  vault policy write "$p" "$p.hcl"
done
vault write auth/approle/role/sample-app token_policies=platform-crypto-app token_ttl=1h >/dev/null
role_id="$(vault read -field=role_id auth/approle/role/sample-app/role-id)"
secret_id="$(vault write -f -field=secret_id auth/approle/role/sample-app/secret-id)"
vault kv put secret/platform/notifications/mail-password value="$mail_password" >/dev/null
vault kv put secret/platform/int/ENT-DEMO-0001/api-key value="$api_key" >/dev/null

step "2. The other systems: login system, API destination, mail server"
java -jar "$(native "$MOCKS_JAR")" "$(native "$work/mocks.env")" > mocks.log 2>&1 & pids="$pids $!"
for _ in $(seq 1 60); do [ -s mocks.env ] && break; sleep 1; done
[ -s mocks.env ] || fail "the mocks did not start"
# shellcheck disable=SC1091
. ./mocks.env

step "3. Database, owner and DML-only user"
psql -v ON_ERROR_STOP=1 -q -d postgres <<EOF
create role app_owner login password '$owner_password';
create role app_runtime login password '$runtime_password';
create database sampledb owner app_owner;
EOF
psql -v ON_ERROR_STOP=1 -q -d sampledb -c "create schema app authorization app_owner" -c "grant usage on schema app to app_runtime"
db_url="jdbc:postgresql://127.0.0.1:${PGPORT}/sampledb?currentSchema=app"

step "4. The schema step (every module's own Flyway run), as the schema owner"
SCHEMA_DB_URL="$db_url" SCHEMA_DB_USER=app_owner SCHEMA_DB_PASSWORD="$owner_password" SCHEMA_NAME=app \
  java -jar "$(native "$APP_JAR")" schema
step "4b. The grants the guides list"
PGUSER=app_owner PGPASSWORD="$owner_password" PGOPTIONS="-c search_path=app" psql -v ON_ERROR_STOP=1 -q -d sampledb -f "$(native "$here/grants.sql")"

step "5. Setup: plan, apply, apply again (docs/install/setup.md)"
cp "$repo"/platform-setup/demo/*.csv "$repo"/platform-setup/demo/*.json .
jq --arg issuer "$ISSUER" '.loginSystem.issuer=$issuer | .products |= {PRODUCT_A: .PRODUCT_A}
  | .products.PRODUCT_A.integrationProfiles=[{name:"acme-archive-v1",file:"acme-archive-v1.profile.json",tag:"acme-archive-profile-v1.2.0"}]' \
  signin-demo.json > signin.json
cp "$here/acme-archive-v1.profile.json" .
setup_token="$(vault token create -policy=platform-crypto-setup -ttl=15m -field=token)"
run_setup() {
  PLATFORM_SETUP_DB_URL="$db_url" PLATFORM_SETUP_DB_USER=app_runtime PLATFORM_SETUP_DB_PASSWORD="$runtime_password" \
  PLATFORM_SETUP_VAULT_ADDR="$VAULT_ADDR" PLATFORM_SETUP_VAULT_TOKEN="$setup_token" \
    java -jar "$(native "$APP_JAR")" setup "$1" --licence licence-demo.json --signin signin.json --product PRODUCT_A \
    --product-roles product-roles-product-a.json
}
run_setup plan; [ ! -f platform-tenant.properties ] || fail "plan wrote the config file"
run_setup apply
run_setup apply > again.txt; cat again.txt; ! grep -q "^  create" again.txt || fail "a second apply creates something"
[ -f platform-tenant.properties ] || fail "no platform-tenant.properties"
vault token revoke "$setup_token" >/dev/null

step "6. The app's settings"
cat > sample-install.properties <<EOF
spring.datasource.url=$db_url
spring.datasource.username=app_runtime
platform.crypto.vault.address=$VAULT_ADDR
platform.crypto.vault.role-id=$role_id
platform.erase-log.location=$(native "$work/erase-log")
platform.outbound.installation=ON_PREMISES
platform.outbound.allow-internal-addresses=true
platform.integration.profile-folder=$(native "$work")
platform.notifications.mail.host=127.0.0.1
platform.notifications.mail.port=$MAIL_PORT
platform.notifications.mail.security=NONE
platform.notifications.mail.username=notify
platform.notifications.mail.password-secret=notifications/mail-password
platform.notifications.mail.from-address=notify@sample.example
platform.notifications.sender.poll-interval=1s
platform.monitoring.secrets=notifications/mail-password
platform.monitoring.folders.export.path=$(native "$work/export")
sample.export-folder=$(native "$work/export")
sample.push-address=$DESTINATION
EOF
export SPRING_DATASOURCE_PASSWORD="$runtime_password" PLATFORM_CRYPTO_VAULT_SECRETID="$secret_id" \
  SAMPLE_MONITORINGPASSWORD="$monitoring_password"
base=http://127.0.0.1:8080
app_pid=""
start_app() {
  java -jar "$(native "$APP_JAR")" "$@" > app.log 2>&1 & app_pid=$!; pids="$pids $app_pid"
  for _ in $(seq 1 120); do
    kill -0 "$app_pid" 2>/dev/null || fail "the app stopped while starting"
    code="$(curl -s -o /dev/null -w '%{http_code}' "$base/actuator/health/liveness" || true)"
    [ "$code" = 200 ] && return 0
    sleep 1
  done
  fail "the app did not start"
}
stop_app() { kill "$app_pid" 2>/dev/null || true; wait "$app_pid" 2>/dev/null || true; sleep 2; }
as_monitoring() { curl -s -u "monitoring:$monitoring_password" "$@"; }
smoke() {
  local out; out="$(as_monitoring -X POST -H 'Content-Type: text/plain' --data-binary "${2:-}" "$base/smoke/$1")"
  echo "smoke $1: $out"; case "$out" in OK*) ;; *) fail "smoke step $1" ;; esac
}

step "7. Start with the DML-only user: readiness UP, every check WORKING"
start_app
curl -s "$base/actuator/health/readiness" | tee readiness.json; echo
[ "$(jq -r .status readiness.json)" = UP ] || fail "readiness is not UP"
for _ in $(seq 1 30); do
  as_monitoring "$base/actuator/health/dependencies" > deps.json
  bad="$(jq -r '[.details | to_entries[] | select(.value.state != "WORKING") | "\(.key)=\(.value.code)"] | join(" ")' deps.json)"
  [ -z "$bad" ] && break; sleep 2
done
jq -c '.details | map_values(.state)' deps.json
[ -z "$bad" ] || { jq . deps.json; fail "not working: $bad"; }

step "8. Smoke"
smoke sign-in "$(curl -s "$ADMIN/token")"
smoke activate
smoke field
smoke export
smoke push; [ "$(curl -s "$ADMIN/pushes")" -ge 1 ] || fail "the destination received nothing"
smoke model
smoke notify
for _ in $(seq 1 30); do [ "$(curl -s "$ADMIN/mails")" -ge 1 ] && break; sleep 1; done
[ "$(curl -s "$ADMIN/mails")" -ge 1 ] || fail "no mail arrived"
smoke bundle "$(printf '%s\n' "$runtime_password" "$owner_password" "$secret_id" "$mail_password" "$api_key" "$monitoring_password" "$VAULT_TOKEN")"
smoke intake

step "9. Erasure, database restore, replay (docs/install/erase-log.md section 5)"
PGUSER=app_owner PGPASSWORD="$owner_password" pg_dump -Fc -n app -f backup.dump sampledb
smoke erase
stop_app
psql -v ON_ERROR_STOP=1 -q -d postgres -c "select pg_terminate_backend(pid) from pg_stat_activity where datname='sampledb'" >/dev/null
psql -v ON_ERROR_STOP=1 -q -d sampledb -c "drop schema app cascade"
pg_restore --exit-on-error -d sampledb backup.dump
start_app
as_monitoring "$base/actuator/health/eraseLog" | tee erase.json; echo
[ "$(jq -r .details.code erase.json)" = ERASE_LOG_RESTORE_DETECTED ] || fail "the restore was not noticed"
smoke field-present
stop_app
java -jar "$(native "$APP_JAR")" --erase-replay 2>&1 | tee replay.log | tail -20
start_app
[ "$(as_monitoring "$base/actuator/health/eraseLog" | jq -r .status)" = UP ] || fail "not UP after the replay"
smoke field-gone
stop_app

step "10. Licensing: read-only and enforcing"
start_app --sample.licensing=enforce
smoke intake; stop_app
start_app --sample.licensing=enforce --platform.licensing.product-code=PRODUCT_C --platform.identity.push.product-code=PRODUCT_C
out="$(as_monitoring -X POST "$base/smoke/intake")"; echo "$out"
case "$out" in "OK REFUSED"*) ;; *) fail "a product the licence does not list was not refused" ;; esac
stop_app
start_app --sample.licensing=read-only --platform.licensing.product-code=PRODUCT_C --platform.identity.push.product-code=PRODUCT_C
out="$(as_monitoring -X POST "$base/smoke/intake")"; echo "$out"
case "$out" in "OK ACCEPTED"*) ;; *) fail "read-only licensing refused work" ;; esac
stop_app

step "11. Start-up refusals"
refused() { # code
  if timeout 180 java -jar "$(native "$APP_JAR")" > refused.log 2>&1; then fail "the app started; $1 expected"; fi
  grep -n "$1" refused.log | head -3 || { grep -vE '^[[:space:]]*(at |...)' refused.log | tail -40 | cut -c1-600; fail "no $1 in the refusal"; }
}
hex="$(vault list -format=json transit/keys | jq -r '.[]' | grep -v -- '-fp$' | head -1)"
eval "$DB_STOP"; refused "${DB_DOWN_CODE:-MON_DB_UNREACHABLE}"; eval "$DB_START"
for _ in $(seq 1 60); do psql -q -d sampledb -c "select 1" >/dev/null 2>&1 && break; sleep 1; done
for k in "$hex" "$hex-fp"; do vault write "transit/keys/$k/config" deletion_allowed=true >/dev/null; vault delete "transit/keys/$k" >/dev/null; done
refused CRYPTO_KEY_MISSING

step "PROOF PASSED"
