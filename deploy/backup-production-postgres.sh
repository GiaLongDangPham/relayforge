#!/usr/bin/env bash
set -euo pipefail

# Run as root on the EC2 host. It creates a PostgreSQL custom-format archive
# and validates that archive without restoring production data.

readonly DEPLOY_DIR="/opt/relayforge"
readonly ENV_FILE="${DEPLOY_DIR}/.env.production"
readonly COMPOSE_FILE="${DEPLOY_DIR}/compose.production.yml"
readonly BACKUP_DIR="${1:-${DEPLOY_DIR}/backups}"

if [[ ${EUID} -ne 0 ]]; then
  echo "Run with sudo: sudo ${0} [backup-directory]" >&2
  exit 2
fi

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

readonly DB_NAME="$(env_value RELAYFORGE_DB_NAME)"
readonly DB_USERNAME="$(env_value RELAYFORGE_DB_USERNAME)"
readonly IMAGE_TAG="$(env_value RELAYFORGE_IMAGE_TAG)"
readonly TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
readonly ARCHIVE_NAME="relayforge-${TIMESTAMP}-${IMAGE_TAG}.dump"
readonly ARCHIVE_PATH="${BACKUP_DIR}/${ARCHIVE_NAME}"
readonly TEMPORARY_PATH="${ARCHIVE_PATH}.partial"

umask 077
install -d -m 700 "${BACKUP_DIR}"

cleanup() {
  rm -f "${TEMPORARY_PATH}"
}
trap cleanup EXIT

docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" exec -T postgres \
  pg_dump --username="${DB_USERNAME}" --format=custom --no-owner --no-privileges "${DB_NAME}" \
  > "${TEMPORARY_PATH}"

if [[ ! -s "${TEMPORARY_PATH}" ]]; then
  echo "PostgreSQL backup archive is empty." >&2
  exit 1
fi

# Parsing the archive proves it is structurally readable. It deliberately does
# not restore anything into the running production database.
docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" exec -T postgres \
  pg_restore --list < "${TEMPORARY_PATH}" > /dev/null

mv "${TEMPORARY_PATH}" "${ARCHIVE_PATH}"
sha256sum "${ARCHIVE_PATH}" > "${ARCHIVE_PATH}.sha256"
printf 'created_at_utc=%s\nimage_tag=%s\narchive=%s\n' \
  "${TIMESTAMP}" "${IMAGE_TAG}" "${ARCHIVE_NAME}" > "${ARCHIVE_PATH}.metadata"

echo "Validated PostgreSQL backup: ${ARCHIVE_PATH}"
