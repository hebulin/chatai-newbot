#!/usr/bin/env bash
set -euo pipefail

APP_NAME="chatai-with-newbot"
APP_DIR="/opt/${APP_NAME}"
RELEASES_DIR="${APP_DIR}/releases"
CURRENT_JAR="${APP_DIR}/current.jar"
SERVICE_NAME="${APP_NAME}"
NEW_RELEASE="${1:-}"

if [[ -z "${NEW_RELEASE}" ]]; then
  echo "Usage: $0 <release-jar-name>"
  exit 1
fi

NEW_JAR="${RELEASES_DIR}/${NEW_RELEASE}"
if [[ ! -f "${NEW_JAR}" ]]; then
  echo "Release jar not found: ${NEW_JAR}"
  exit 1
fi

PREVIOUS_TARGET=""
if [[ -L "${CURRENT_JAR}" ]]; then
  PREVIOUS_TARGET="$(readlink -f "${CURRENT_JAR}")"
elif [[ -f "${CURRENT_JAR}" ]]; then
  PREVIOUS_TARGET="${CURRENT_JAR}"
fi

echo "Deploying ${NEW_JAR}"
ln -sfn "${NEW_JAR}" "${CURRENT_JAR}"

sudo /usr/bin/systemctl daemon-reload
sudo /usr/bin/systemctl restart "${SERVICE_NAME}"
sleep 8

if sudo /usr/bin/systemctl is-active --quiet "${SERVICE_NAME}"; then
  echo "Deploy success: ${NEW_RELEASE}"
  ls -1t "${RELEASES_DIR}"/*.jar 2>/dev/null | tail -n +6 | xargs -r rm -f
  exit 0
fi

echo "Deploy failed, printing service status..."
sudo /usr/bin/systemctl status "${SERVICE_NAME}" --no-pager || true

echo "Trying rollback..."
if [[ -n "${PREVIOUS_TARGET}" && -f "${PREVIOUS_TARGET}" ]]; then
  ln -sfn "${PREVIOUS_TARGET}" "${CURRENT_JAR}"
  sudo /usr/bin/systemctl restart "${SERVICE_NAME}"
  sleep 8
  sudo /usr/bin/systemctl is-active --quiet "${SERVICE_NAME}" && echo "Rollback success" && exit 1
fi

echo "Rollback unavailable or failed"
exit 1
