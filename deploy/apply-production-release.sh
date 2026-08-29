#!/usr/bin/env bash
set -euo pipefail

# Run as root on the EC2 host. This changes only RELAYFORGE_IMAGE_TAG in the
# root-owned runtime file; runtime secrets remain on the host and never enter CI.

readonly DEPLOY_DIR="/opt/relayforge"
readonly ENV_FILE="${DEPLOY_DIR}/.env.production"
readonly COMPOSE_FILE="${DEPLOY_DIR}/compose.production.yml"
readonly HEALTH_TIMEOUT_SECONDS=150
readonly GATEWAY_TIMEOUT_SECONDS=60

if [[ ${EUID} -ne 0 ]]; then
  echo "Run with sudo: sudo ${0} <immutable-git-sha-tag>" >&2
  exit 2
fi

if [[ $# -ne 1 || ! "$1" =~ ^[0-9a-f]{7,64}$ ]]; then
  echo "Supply one lowercase immutable Git SHA tag (7-64 hexadecimal characters)." >&2
  exit 2
fi

readonly NEW_TAG="$1"

if [[ ! -r "${ENV_FILE}" || ! -r "${COMPOSE_FILE}" ]]; then
  echo "RelayForge production environment or Compose file is unreadable." >&2
  exit 2
fi

env_value() {
  local key="$1"
  local line
  line="$(grep -E "^${key}=" "${ENV_FILE}" | tail -n 1 || true)"
  if [[ -z "${line}" ]]; then
    echo "Missing ${key} in ${ENV_FILE}." >&2
    exit 2
  fi
  printf '%s' "${line#*=}"
}

set_image_tag() {
  sed -i "s/^RELAYFORGE_IMAGE_TAG=.*/RELAYFORGE_IMAGE_TAG=$1/" "${ENV_FILE}"
}

compose() {
  docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" "$@"
}

wait_for_healthy() {
  local service="$1"
  local deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
  local container_id
  local health

  while (( SECONDS < deadline )); do
    container_id="$(compose ps -q "${service}")"
    if [[ -n "${container_id}" ]]; then
      health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${container_id}")"
      if [[ "${health}" == "healthy" ]]; then
        return 0
      fi
      if [[ "${health}" == "unhealthy" || "${health}" == "exited" || "${health}" == "dead" ]]; then
        echo "${service} reached unhealthy state: ${health}" >&2
        return 1
      fi
    fi
    sleep 3
  done

  echo "Timed out waiting for ${service} health." >&2
  return 1
}

wait_for_gateway_https() {
  local deadline=$((SECONDS + GATEWAY_TIMEOUT_SECONDS))

  # Resolve the public hostname to loopback. This exercises Caddy's TLS/SNI and
  # host routing without making the host's own readiness depend on AWS public
  # IP hairpin routing or DuckDNS propagation.
  while (( SECONDS < deadline )); do
    if curl --fail --silent --connect-timeout 3 --max-time 5 \
      --resolve "${DOMAIN}:443:127.0.0.1" \
      "https://${DOMAIN}/" > /dev/null; then
      return 0
    fi
    sleep 3
  done

  echo "Timed out waiting for gateway HTTPS on ${DOMAIN}." >&2
  return 1
}

readonly DOMAIN="$(env_value RELAYFORGE_DOMAIN)"
readonly PREVIOUS_TAG="$(env_value RELAYFORGE_IMAGE_TAG)"

if [[ "${PREVIOUS_TAG}" == "${NEW_TAG}" ]]; then
  if ! compose config --quiet; then
    echo "Production Compose validation failed for selected tag ${NEW_TAG}." >&2
    exit 1
  fi
  wait_for_healthy api
  wait_for_healthy worker
  if ! wait_for_gateway_https; then
    compose ps gateway
    compose logs --tail=80 gateway
    exit 1
  fi
  compose ps api worker gateway
  echo "Production already selects ${NEW_TAG}; running services were verified."
  exit 0
fi

set_image_tag "${NEW_TAG}"

if ! compose config --quiet; then
  set_image_tag "${PREVIOUS_TAG}"
  echo "Compose validation failed; restored the previous selected image tag." >&2
  exit 1
fi

if ! compose pull api worker gateway; then
  set_image_tag "${PREVIOUS_TAG}"
  echo "Image pull failed; restored the previous selected image tag." >&2
  exit 1
fi

# API owns Flyway. Do not let a worker use the new image before API readiness.
compose up -d --no-deps --force-recreate api
if ! wait_for_healthy api; then
  echo "New API is not healthy. The selected tag remains ${NEW_TAG} for diagnosis; do not roll back blindly after a migration." >&2
  exit 1
fi

compose up -d --no-deps --force-recreate worker
wait_for_healthy worker

compose up -d --no-deps --force-recreate gateway
if ! wait_for_gateway_https; then
  compose ps gateway
  compose logs --tail=80 gateway
  exit 1
fi

compose ps api worker gateway
echo "Production release ${NEW_TAG} is healthy. Previous tag was ${PREVIOUS_TAG}."
