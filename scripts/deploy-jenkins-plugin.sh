#!/usr/bin/env bash
# Deploy built .hpi to Jenkins: optional uninstall, upload, safeRestart.
# Requires: curl, jq
# CI variables: JENKINS_URL, JENKINS_USER, JENKINS_TOKEN (API token)

set -euo pipefail

JENKINS_URL="${JENKINS_URL%/}"
PLUGIN_ID="${JENKINS_PLUGIN_ID:-jtm-test-management}"
HPI_GLOB="${JENKINS_HPI_GLOB:-target/*.hpi}"

if [[ -z "${JENKINS_USER:-}" || -z "${JENKINS_TOKEN:-}" ]]; then
  echo "ERROR: JENKINS_USER and JENKINS_TOKEN must be set (use a Jenkins API token as password)." >&2
  exit 1
fi

shopt -s nullglob
HPI_FILES=( $HPI_GLOB )
if [[ ${#HPI_FILES[@]} -eq 0 ]]; then
  echo "ERROR: No .hpi found matching $HPI_GLOB (run Maven package first)." >&2
  exit 1
fi
#
# Pick the newest artifact (avoid relying on filesystem glob order).
#
HPI_NEWEST="$(
  for f in "${HPI_FILES[@]}"; do
    # %Y = mtime in seconds (epoch), stable for sorting.
    # shellcheck disable=SC2001
    printf '%s %s\n' "$(stat -c '%Y' "$f")" "$f"
  done | sort -nr | head -n1 | cut -d' ' -f2- \
)"
echo "HPI candidates (newest first):"
for f in "${HPI_FILES[@]}"; do
  printf ' - %s\n' "$f"
done
echo "Using artifact: $HPI_NEWEST"
HPI="${HPI_NEWEST}"

AUTH=(-u "${JENKINS_USER}:${JENKINS_TOKEN}")

# CSRF crumb (skip header if issuer disabled / 404)
CRUMB_HEADERS=()
if CRUMB_JSON="$(curl -sfS "${AUTH[@]}" "${JENKINS_URL}/crumbIssuer/api/json" 2>/dev/null)"; then
  CRUMB="$(echo "$CRUMB_JSON" | jq -r '.crumb')"
  FIELD="$(echo "$CRUMB_JSON" | jq -r '.crumbRequestField')"
  if [[ -n "$CRUMB" && "$CRUMB" != "null" ]]; then
    CRUMB_HEADERS=(-H "${FIELD}: ${CRUMB}")
    echo "CSRF crumb acquired ($FIELD)."
  fi
else
  echo "No crumb issuer (or unreachable); continuing without crumb."
fi

# Optional: uninstall existing plugin (ignore failure if not installed)
echo "Attempting uninstall of ${PLUGIN_ID} (if present)..."
U_CODE=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "${AUTH[@]}" "${CRUMB_HEADERS[@]}" \
  "${JENKINS_URL}/pluginManager/plugin/${PLUGIN_ID}/uninstall" || echo "000")
if [[ "$U_CODE" =~ ^(200|302|303)$ ]]; then
  echo "Uninstall request accepted (HTTP $U_CODE)."
else
  echo "Uninstall skipped or not applicable (HTTP $U_CODE)."
fi

echo "Uploading plugin..."
# Jenkins advanced.jelly: multipart order is (1) file field "name", (2) text "pluginUrl".
# PluginManager#doUploadPlugin reads items.get(1) before branching — a single-part upload
# causes IndexOutOfBoundsException → HTTP 500. Always send an empty pluginUrl field.
HPI_BASE="$(basename "$HPI")"
UPLOAD_PARTS=(
  -F "name=@${HPI};filename=${HPI_BASE}"
  -F "pluginUrl="
)
if [[ -n "${CRUMB:-}" && "$CRUMB" != "null" && -n "${FIELD:-}" ]]; then
  UPLOAD_PARTS+=( -F "${FIELD}=${CRUMB}" )
fi

HTTP_UPLOAD=$(curl -sS -w '%{http_code}' -o /tmp/jtm-upload.out \
  "${AUTH[@]}" "${CRUMB_HEADERS[@]}" \
  -X POST \
  "${UPLOAD_PARTS[@]}" \
  "${JENKINS_URL}/pluginManager/uploadPlugin") || true

if [[ "$HTTP_UPLOAD" != "200" && "$HTTP_UPLOAD" != "302" && "$HTTP_UPLOAD" != "303" ]]; then
  echo "Upload HTTP status: $HTTP_UPLOAD"
  head -c 2000 /tmp/jtm-upload.out || true
  echo "" >&2
  echo "ERROR: Upload failed. Check user rights (Overall/Administer or PluginManager upload) and crumb/CSRF settings." >&2
  exit 1
fi
echo "Upload OK (HTTP $HTTP_UPLOAD)."

echo "Triggering safe restart..."
# Jenkins may close the connection while restarting; do not fail the job on curl errors here
set +e
curl -sS -X POST "${AUTH[@]}" "${CRUMB_HEADERS[@]}" \
  "${JENKINS_URL}/safeRestart" -o /tmp/jtm-restart.out -w "%{http_code}\n"
RC=$?
set -e
echo "safeRestart request finished (curl exit $RC). Jenkins will restart when idle."

exit 0
