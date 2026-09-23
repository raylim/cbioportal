#!/usr/bin/env bash
set -euo pipefail

# Run the WSI integration contract against the exact backend/Core/frontend/tile
# tuple used by the validation manifest.  This script is intentionally kept in
# the validation PR: it is a release gate and is never part of ordinary portal
# publication.

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_STACK_DIR="${TEST_STACK_DIR:?TEST_STACK_DIR must point at the pinned test-stack checkout}"
FRONTEND_DIR="${FRONTEND_DIR:?FRONTEND_DIR must point at the pinned frontend integration checkout}"
TILE_DIR="${TILE_DIR:?TILE_DIR must point at the pinned tile-server checkout}"
CORE_REPOSITORY="${CORE_REPOSITORY:?CORE_REPOSITORY is required}"
CORE_REF="${CORE_REF:?CORE_REF is required}"
COMPOSE_REPOSITORY="${COMPOSE_REPOSITORY:?COMPOSE_REPOSITORY is required}"
COMPOSE_REF="${COMPOSE_REF:?COMPOSE_REF is required}"
IMAGE="${WSI_PORTAL_IMAGE:-cbioportal-wsi-validation:local}"
PROJECT="${COMPOSE_PROJECT_NAME:-wsi-validation}"
AUTH_SECRET="${WSI_AUTH_SECRET:-local-development-wsi-secret-change-me-32chars}"
AUTH_AUDIENCE="${WSI_AUTH_AUDIENCE:-cbioportal-wsi}"
FRONTEND_PORT="${FRONTEND_PORT:-3000}"

test -x "$TEST_STACK_DIR/utils/check-connection.sh"

COMPOSE_DIR="${RUNNER_TEMP:-/tmp}/wsi-validation-compose"
COMPOSE_OVERRIDE="${ROOT_DIR}/src/e2e/js/test/WsiHierarchyController/wsi_ci_compose_override.yml"
COMPOSE_AUTH_OVERRIDE="${ROOT_DIR}/src/e2e/js/test/WsiHierarchyController/wsi_ci_auth_compose_override.yml"
FRONTEND_LOG="${RUNNER_TEMP:-/tmp}/wsi-frontend.log"
COMPOSE_LOG="${RUNNER_TEMP:-/tmp}/wsi-compose.log"
TILE_LOG="${RUNNER_TEMP:-/tmp}/wsi-tile.log"
FULL_STACK_LOG="${RUNNER_TEMP:-/tmp}/wsi-full-stack.log"
ACTIVE_COMPOSE_FILES=()
STACK_STARTED=false

# Keep the command-level trace alongside the service logs.  The compose log
# contains container output, but importer/API/browser failures happen in this
# shell and otherwise leave only an exit code in the Actions UI.
exec > >(tee -a "$FULL_STACK_LOG") 2>&1

log() {
  printf '[wsi-full-stack] %s\n' "$*"
}

wait_http() {
  local url="$1"
  local retries="${2:-180}"
  local insecure="${3:-}"
  for _ in $(seq 1 "$retries"); do
    if curl --fail --silent --show-error ${insecure:+--insecure} "$url" >/dev/null; then
      return 0
    fi
    sleep 2
  done
  return 1
}

compose() {
  docker compose -p "$PROJECT" -f "$COMPOSE_DIR/docker-compose.yml" "$@"
}

stop_frontend() {
  if [[ -n "${FRONTEND_PID:-}" ]]; then
    kill "$FRONTEND_PID" 2>/dev/null || true
    wait "$FRONTEND_PID" 2>/dev/null || true
    unset FRONTEND_PID
  fi
}

stop_tile() {
  docker rm -f wsi-validation-tile wsi-validation-redis >/dev/null 2>&1 || true
}

stop_compose() {
  stop_frontend
  if [[ ${#ACTIVE_COMPOSE_FILES[@]} -gt 0 ]]; then
    compose "${ACTIVE_COMPOSE_FILES[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  else
    compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  fi
  stop_tile
}

cleanup() {
  set +e
  if [[ "$STACK_STARTED" == true ]]; then
    {
      echo '--- compose ps ---'
      compose ps
      echo '--- cbioportal logs ---'
      compose logs --no-color cbioportal
      echo '--- migration logs ---'
      compose logs --no-color cbioportal-migration
    } >>"$COMPOSE_LOG" 2>&1
  fi
  stop_compose
}
trap cleanup EXIT

build_portal_image() {
  log "building portal image ${IMAGE} with Core ${CORE_REF}"
  docker build \
    --build-arg CORE_REPOSITORY="$CORE_REPOSITORY" \
    --build-arg CORE_REF="$CORE_REF" \
    --tag "$IMAGE" \
    --file "$ROOT_DIR/docker/web-and-data/Dockerfile" \
    "$ROOT_DIR"
}

build_tile_image() {
  log "building pinned tile image"
  docker build --tag wsi-validation-tile-image:local "$TILE_DIR"
}

start_tile() {
  local port="$1"
  stop_tile
  docker network create wsi-validation-tile-net >/dev/null 2>&1 || true
  docker run -d \
    --name wsi-validation-redis \
    --network wsi-validation-tile-net \
    redis:7.4.5-alpine redis-server --save '' --appendonly no >/dev/null
  docker run -d \
    --name wsi-validation-tile \
    --network wsi-validation-tile-net \
    --publish "${port}:8080" \
    --link wsi-validation-redis:redis \
    --env WSI_AUTH_SECRET="$AUTH_SECRET" \
    --env WSI_AUTH_AUDIENCE="$AUTH_AUDIENCE" \
    --env WSI_ALLOWED_SOURCE_SCHEMES=file \
    --env WSI_ALLOWED_SOURCE_PREFIXES=file:///app/testdata/ \
    --env WSI_ALLOWED_THUMBNAIL_PREFIXES=file:///app/testdata/ \
    --env REDIS_URL=redis://redis:6379 \
    --volume "$TILE_DIR/tests/testdata:/app/testdata:ro" \
    wsi-validation-tile-image:local \
    >"$TILE_LOG" 2>&1
  wait_http "http://127.0.0.1:${port}/ready" 120
}

prepare_compose() {
  log "preparing pinned compose stack"
  rm -rf "$COMPOSE_DIR"
  git clone --filter=blob:none --no-checkout \
    "https://github.com/${COMPOSE_REPOSITORY}.git" "$COMPOSE_DIR"
  git -C "$COMPOSE_DIR" fetch --depth 1 origin "$COMPOSE_REF"
  git -C "$COMPOSE_DIR" checkout --detach "$COMPOSE_REF"
  cat >"$COMPOSE_DIR/.env" <<EOF
DOCKER_IMAGE_CBIOPORTAL=${IMAGE}
DOCKER_IMAGE_SESSION_SERVICE=cbioportal/session-service:0.6.4
DOCKER_IMAGE_CLICKHOUSE=clickhouse/clickhouse-server:24.10
CLICKHOUSE_DB=cbioportal
CLICKHOUSE_USER=cbio_user
CLICKHOUSE_PASSWORD=somepassword
CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=1
CLICKHOUSE_HOST=cbioportal-database
CLICKHOUSE_HTTP_PORT=8123
CLICKHOUSE_NATIVE_PORT=9000
# The ClickHouse JDBC driver does not inherit the server profile for this
# setting. Pass it explicitly so lifecycle DELETE uses projection-safe mode.
CLICKHOUSE_URL=jdbc:ch://cbioportal-database:8123/cbioportal?custom_settings=lightweight_mutation_projection_mode%3Ddrop
CLICKHOUSE_OPTIMIZE_BACKOFF_SECS=0
CLICKHOUSE_SETTINGS_PATH=./data/clickhouse_user_settings.xml
CBIOPORTAL_SERVER_PORT=8080
SHOW_DEBUG_INFO=true
APPLICATION_PROPERTIES_PATH=./application.properties
SESSION_SERVICE_SERVER_PORT=5001
SESSION_SERVICE_JAVA_OPTS=-Dspring.data.mongodb.uri=mongodb://cbioportal-session-database:27017/session-service
MONGO_INITDB_DATABASE=session_service
CBIOPORTAL_SOURCE_DIR=${ROOT_DIR}
WSI_AUTH_SECRET=${AUTH_SECRET}
WSI_AUTH_AUDIENCE=${AUTH_AUDIENCE}
WSI_ALLOWED_SOURCE_PREFIXES=file:///app/testdata/
WSI_ALLOWED_THUMBNAIL_PREFIXES=file:///app/testdata/
EOF
  mkdir -p "$COMPOSE_DIR/study"
  (cd "$COMPOSE_DIR" && ./config/init.sh && ./data/init.sh)
}

start_compose() {
  local mode="$1"
  local tile_port="$2"
  local portal_authenticate="$3"
  local local_bypass="$4"
  local compose_files=(-f "$COMPOSE_DIR/docker-compose.yml" -f "$COMPOSE_OVERRIDE")

  export WSI_AUTHENTICATE="$portal_authenticate"
  export WSI_LOCAL_AUTH_BYPASS="$local_bypass"
  export WSI_TILE_SERVER_URL="http://localhost:${tile_port}"
  export WSI_BASIC_USERNAME=wsi-ci-user
  export WSI_BASIC_PASSWORD='$2b$10$7MoXjWmDD/mrvq7hF9D0RuFewhTzl8KYATtySCSZX7tMV.614kO3.'
  export WSI_BASIC_AUTHORITIES=PUBLIC_STUDIES
  export WSI_REQUIRE_AUTHENTICATED_SETUP=false
  if [[ "$mode" == authenticated ]]; then
    compose_files+=(-f "$COMPOSE_DIR/dev/keycloak/keycloak.yml" -f "$COMPOSE_AUTH_OVERRIDE")
    export WSI_REQUIRE_AUTHENTICATED_SETUP=true
    compose -f "$COMPOSE_DIR/dev/keycloak/keycloak.yml" up keycloak -d >"$COMPOSE_LOG" 2>&1
    wait_http http://127.0.0.1:8081/auth/realms/cbio/protocol/saml/descriptor 180
    curl --fail --silent http://127.0.0.1:8081/auth/realms/cbio/protocol/saml/descriptor \
      >"$COMPOSE_DIR/dev/keycloak/idp-metadata.xml"
  fi

  log "starting ${mode} portal stack"
  ACTIVE_COMPOSE_FILES=("${compose_files[@]}")
  compose "${compose_files[@]}" up -d >"$COMPOSE_LOG" 2>&1
  STACK_STARTED=true
  wait_http http://127.0.0.1:8080/api/health 180
  log "checking ClickHouse projection-delete setting"
  docker inspect cbioportal-database-container \
    --format '{{range .Mounts}}{{println .Source " -> " .Destination}}{{end}}' \
    | tee -a "$COMPOSE_LOG"
  docker exec cbioportal-database-container \
    sh -lc 'cat /etc/clickhouse-server/users.d/wsi-ci.xml' \
    | tee -a "$COMPOSE_LOG"
  docker exec cbioportal-database-container sh -lc '
    clickhouse-client --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" \
      --database "$CLICKHOUSE_DB" \
      --query "SELECT name, value, changed FROM system.settings WHERE name = '\''lightweight_mutation_projection_mode'\''"
  ' | tee -a "$COMPOSE_LOG"
}

import_fixture_and_check_lifecycle() {
  log "importing WSI fixtures"
  for _ in $(seq 1 150); do
    if docker exec cbioportal-database-container sh -lc \
      'clickhouse-client --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --database "$CLICKHOUSE_DB" --query "SELECT 1"' \
      >/dev/null 2>&1; then
      break
    fi
    sleep 2
  done
  docker exec cbioportal-container sh -lc '
    export PORTAL_HOME=/cbioportal-webapp
    export CLICKHOUSE_HOST="${CLICKHOUSE_HOST:-cbioportal-database}"
    export CLICKHOUSE_NATIVE_PORT="${CLICKHOUSE_NATIVE_PORT:-9000}"
    export CLICKHOUSE_DB="${CLICKHOUSE_DB:-cbioportal}"
    export JAVA_OPTS="-Dspring.datasource.url=$CLICKHOUSE_URL -Dspring.datasource.username=$CLICKHOUSE_USER -Dspring.datasource.password=$CLICKHOUSE_PASSWORD -Dspring.datasource.driver-class-name=com.clickhouse.jdbc.ClickHouseDriver"
    python3 /core/scripts/importer/metaImport.py -s /tmp/wsi-loader-fixture -n -o --derived-table-sql /tmp/wsi-clickhouse.sql
  '
  docker exec cbioportal-container sh -lc '
    export PORTAL_HOME=/cbioportal-webapp
    export CLICKHOUSE_HOST="${CLICKHOUSE_HOST:-cbioportal-database}"
    export CLICKHOUSE_NATIVE_PORT="${CLICKHOUSE_NATIVE_PORT:-9000}"
    export CLICKHOUSE_DB="${CLICKHOUSE_DB:-cbioportal}"
    export JAVA_OPTS="-Dspring.datasource.url=$CLICKHOUSE_URL -Dspring.datasource.username=$CLICKHOUSE_USER -Dspring.datasource.password=$CLICKHOUSE_PASSWORD -Dspring.datasource.driver-class-name=com.clickhouse.jdbc.ClickHouseDriver"
    python3 /core/scripts/importer/metaImport.py -s /tmp/wsi-loader-control-fixture -n -o --derived-table-sql /tmp/wsi-clickhouse.sql
  '
  local slide_count
  slide_count="$(docker exec cbioportal-database-container sh -lc '
    clickhouse-client --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --database "$CLICKHOUSE_DB" --query "SELECT count() FROM wsi_slide AS slide INNER JOIN cancer_study AS study ON study.cancer_study_id = slide.cancer_study_id WHERE study.cancer_study_identifier = '\''msk_spectrum_tme_2022'\''"
  ')"
  test "$slide_count" = 4

  log "checking deletion and clean reimport"
  docker exec cbioportal-container sh -lc '
    export PORTAL_HOME=/cbioportal-webapp
    export CLICKHOUSE_HOST="${CLICKHOUSE_HOST:-cbioportal-database}"
    export CLICKHOUSE_NATIVE_PORT="${CLICKHOUSE_NATIVE_PORT:-9000}"
    export CLICKHOUSE_DB="${CLICKHOUSE_DB:-cbioportal}"
    export JAVA_OPTS="-Dspring.datasource.url=$CLICKHOUSE_URL -Dspring.datasource.username=$CLICKHOUSE_USER -Dspring.datasource.password=$CLICKHOUSE_PASSWORD -Dspring.datasource.driver-class-name=com.clickhouse.jdbc.ClickHouseDriver"
    cbioportalImporter.py -c remove-study -id msk_spectrum_tme_2022
  '
  local remaining
  remaining="$(docker exec cbioportal-database-container sh -lc '
    clickhouse-client --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --database "$CLICKHOUSE_DB" --query "SELECT count() FROM wsi_slide AS slide INNER JOIN cancer_study AS study ON study.cancer_study_id = slide.cancer_study_id WHERE study.cancer_study_identifier = '\''msk_spectrum_tme_2022'\''"
  ')"
  test "$remaining" = 0
  docker exec cbioportal-container sh -lc '
    export PORTAL_HOME=/cbioportal-webapp
    export CLICKHOUSE_HOST="${CLICKHOUSE_HOST:-cbioportal-database}"
    export CLICKHOUSE_NATIVE_PORT="${CLICKHOUSE_NATIVE_PORT:-9000}"
    export CLICKHOUSE_DB="${CLICKHOUSE_DB:-cbioportal}"
    export JAVA_OPTS="-Dspring.datasource.url=$CLICKHOUSE_URL -Dspring.datasource.username=$CLICKHOUSE_USER -Dspring.datasource.password=$CLICKHOUSE_PASSWORD -Dspring.datasource.driver-class-name=com.clickhouse.jdbc.ClickHouseDriver"
    python3 /core/scripts/importer/metaImport.py -s /tmp/wsi-loader-fixture -n -o --derived-table-sql /tmp/wsi-clickhouse.sql
  '
  local reimported
  reimported="$(docker exec cbioportal-database-container sh -lc '
    clickhouse-client --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --database "$CLICKHOUSE_DB" --query "SELECT count() FROM wsi_slide AS slide INNER JOIN cancer_study AS study ON study.cancer_study_id = slide.cancer_study_id WHERE study.cancer_study_identifier = '\''msk_spectrum_tme_2022'\''"
  ')"
  test "$reimported" = 4
}

start_frontend() {
  local tile_port="$1"
  stop_frontend
  log "starting integration frontend"
  (cd "$FRONTEND_DIR" && \
    NODE_ENV=development HOST=0.0.0.0 PORT="$FRONTEND_PORT" \
    CBIOPORTAL_URL="https://localhost:${FRONTEND_PORT}" \
    CBIOPORTAL_PROXY_TARGET=http://localhost:8080 \
    WSI_TILE_PROXY_TARGET="http://localhost:${tile_port}" \
    pnpm exec rspack serve -c rspack.config.js >"$FRONTEND_LOG" 2>&1) &
  FRONTEND_PID=$!
  wait_http "https://127.0.0.1:${FRONTEND_PORT}/" 120 true
}

prepare_frontend() {
  log 'installing frontend dependencies'
  (cd "$FRONTEND_DIR" && pnpm install --frozen-lockfile)
}

run_api_tests() {
  local mode="$1"
  log "running ${mode} authenticated hierarchy/tile API contract"
  (cd "$ROOT_DIR/src/e2e/js" && \
    CBIOPORTAL_URL=http://localhost:8080 \
    CBIOPORTAL_FRONTEND_URL="https://localhost:${FRONTEND_PORT}" \
    WSI_FRONTEND_ALLOW_SELF_SIGNED_TLS=true \
    WSI_AUTH_SECRET="$AUTH_SECRET" \
    WSI_AUTH_AUDIENCE="$AUTH_AUDIENCE" \
    WSI_TILE_SERVER_URL="http://localhost:${WSI_TILE_SERVER_PORT}" \
    WSI_LOCAL_AUTH_BYPASS="$WSI_LOCAL_AUTH_BYPASS" \
    WSI_REQUIRE_AUTHENTICATED_SETUP="$WSI_REQUIRE_AUTHENTICATED_SETUP" \
    WSI_BASIC_LOGIN_PASSWORD=wsi-ci-password \
    yarn test -- test/WsiHierarchyController/WsiHierarchyController.spec.ts \
      --grep 'WsiHierarchyController' --reporter spec)
}

run_browser_tests() {
  local mode="$1"
  log "running ${mode} browser WSI contract"
  local browser_dir="$FRONTEND_DIR/end-to-end-test-playwright"
  (cd "$browser_dir" && \
    CBIOPORTAL_URL="https://localhost:${FRONTEND_PORT}" \
    CBIO_URL=http://localhost:8080 \
    LOCALDEV=0 \
    PW_IGNORE_HTTPS_ERRORS=1 \
    PW_SUITE=wsi \
    WSI_VIEWER_BASE_URL="https://localhost:${FRONTEND_PORT}" \
    WSI_AUTH_PORTAL_URL=http://localhost:8080 \
    WSI_PROXY_REHEARSAL=1 \
    WSI_TIMING_STUDY_ID=msk_spectrum_tme_2022 \
    WSI_TIMING_UNDATED_PATIENT_ID=P-0055908 \
    WSI_UNDATED_PATIENT_ID=P-0055908 \
    WSI_TIMING_UNDATED_SLIDE_COUNT=2 \
    WSI_TIMING_RECORDED_PATIENT_ID=P-0055908 \
    WSI_TIMING_RECORDED_SLIDE_ID=3020726 \
    WSI_TIMING_RECORDED_DATE_DAYS=-17 \
    WSI_TIMING_RECORDED_DATE_SOURCE=RECORDED_PROCEDURE_DATE \
    PLAYWRIGHT_JSON_OUTPUT_NAME=test-results/report.json \
    pnpm exec playwright test --config=playwright.wsi.config.ts \
      tests/wsi-foundation-route.spec.ts \
      tests/wsi-viewer.spec.ts \
      tests/wsi-pathology-mocked.spec.ts \
      tests/pathology-summary.spec.ts \
      tests/pathology-study-clinical-data.spec.ts \
      tests/pathology-timing-contract.spec.ts \
      --grep-invert 'private MSK-IMPACT' --reporter=line,json
    python3 - <<'PY'
import json
from pathlib import Path

report = json.loads(Path('test-results/report.json').read_text())
stats = report.get('stats', {})
assert stats.get('expected', 0) > 0, stats
assert stats.get('skipped', 0) == 0, stats
assert stats.get('unexpected', 0) == 0, stats
print('required full-stack browser cases executed:', stats)
PY
  )
}

build_portal_image
build_tile_image
prepare_compose
prepare_frontend
(cd "$ROOT_DIR/src/e2e/js" && yarn install --frozen-lockfile)
(cd "$FRONTEND_DIR/end-to-end-test-playwright" && pnpm install --frozen-lockfile --ignore-workspace && pnpm exec playwright install --with-deps chromium)

for mode in unauthenticated authenticated; do
  if [[ "$mode" == authenticated ]]; then
    tile_port=8082
    portal_authenticate=saml_plus_basic
    local_bypass=false
    WSI_TILE_SERVER_PORT="$tile_port"
  else
    tile_port=8081
    portal_authenticate=false
    local_bypass=true
    WSI_TILE_SERVER_PORT="$tile_port"
  fi
  start_tile "$tile_port"
  start_compose "$mode" "$tile_port" "$portal_authenticate" "$local_bypass"
  import_fixture_and_check_lifecycle
  start_frontend "$tile_port"
  run_api_tests "$mode"
  run_browser_tests "$mode"
  stop_compose
done

log 'full-stack WSI validation passed in both authentication modes'
