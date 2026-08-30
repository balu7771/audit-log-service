#!/usr/bin/env bash
# Runs a full Sonar analysis against the local docker-compose SonarQube instance,
# writes docs/reports/sonar-report.md, and exits non-zero if the Quality Gate
# fails or any BLOCKER/CRITICAL issue is open - i.e. this is the "blocked for
# further deploy" gate. Re-run any time; the report is overwritten each time
# (see docs/architecture/QUALITY-GATES.md for the full flow and how to wire
# this into a real CI pipeline).
#
# Usage: ./scripts/quality-gate.sh
#
# Local-eval-only shortcut: bootstraps a Sonar token using the default admin/admin
# credentials, changing the password on first run. Never do this against a shared
# or production Sonar instance - use a pre-provisioned token via SONAR_TOKEN instead.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

SONAR_URL="${SONAR_URL:-http://localhost:9000}"
PROJECT_KEY="audit-log-service"
LOCAL_ADMIN_PASSWORD="${SONAR_LOCAL_ADMIN_PASSWORD:-Local-dev-only-change-me1}"
TOKEN_FILE=".sonar-token"
REPORT_FILE="docs/reports/sonar-report.md"

echo "==> Ensuring SonarQube (docker compose --profile quality) is up..."
docker compose --profile quality up -d sonarqube-db sonarqube

echo "==> Waiting for SonarQube to report UP at ${SONAR_URL}..."
for _ in $(seq 1 60); do
  sonar_status=$(curl -sf "${SONAR_URL}/api/system/status" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("status",""))' 2>/dev/null || true)
  [ "$sonar_status" = "UP" ] && break
  sleep 5
done
if [ "${sonar_status:-}" != "UP" ]; then
  echo "SonarQube did not become UP in time" >&2
  exit 1
fi

if [ -n "${SONAR_TOKEN:-}" ]; then
  echo "==> Using SONAR_TOKEN from environment"
  TOKEN="$SONAR_TOKEN"
elif [ -f "$TOKEN_FILE" ]; then
  echo "==> Reusing cached token from $TOKEN_FILE"
  TOKEN=$(cat "$TOKEN_FILE")
else
  echo "==> Bootstrapping admin credentials and a fresh analysis token (local eval only)"
  # First-ever login forces a password change in the UI; harmless to also do it here.
  # Ignored if it fails (e.g. already changed by a previous run of this script).
  curl -s -u admin:admin -X POST "${SONAR_URL}/api/users/change_password" \
    --data-urlencode "login=admin" \
    --data-urlencode "previousPassword=admin" \
    --data-urlencode "password=${LOCAL_ADMIN_PASSWORD}" >/dev/null || true

  # /api/system/status doesn't actually require auth (200 regardless of credentials),
  # so use /api/authentication/validate, which reports {"valid":false} for bad creds
  # while still returning HTTP 200 - check the body, not the status code.
  is_valid_auth() {
    curl -s -u "$1" "${SONAR_URL}/api/authentication/validate" \
      | python3 -c 'import sys,json; sys.exit(0 if json.load(sys.stdin).get("valid") else 1)'
  }
  if is_valid_auth "admin:${LOCAL_ADMIN_PASSWORD}"; then
    ADMIN_AUTH="admin:${LOCAL_ADMIN_PASSWORD}"
  else
    ADMIN_AUTH="admin:admin"
  fi

  curl -s -u "$ADMIN_AUTH" -X POST "${SONAR_URL}/api/user_tokens/revoke" \
    --data-urlencode "name=audit-log-service-ci" >/dev/null || true
  TOKEN=$(curl -sf -u "$ADMIN_AUTH" -X POST "${SONAR_URL}/api/user_tokens/generate" \
    --data-urlencode "name=audit-log-service-ci" \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)["token"])')
  echo "$TOKEN" > "$TOKEN_FILE"
  chmod 600 "$TOKEN_FILE"

  curl -s -u "$ADMIN_AUTH" -X POST "${SONAR_URL}/api/projects/create" \
    --data-urlencode "project=${PROJECT_KEY}" \
    --data-urlencode "name=Audit Log Service" >/dev/null || true
fi

echo "==> Running build + tests + coverage + Sonar analysis..."
GATE_STATUS=0
./mvnw -B verify sonar:sonar \
  -Dsonar.host.url="${SONAR_URL}" \
  -Dsonar.token="${TOKEN}" \
  || GATE_STATUS=$?

echo "==> Fetching quality gate status and Blocker/Critical issues..."
mkdir -p docs/reports
python3 - "$SONAR_URL" "$PROJECT_KEY" "$TOKEN" "$REPORT_FILE" "$GATE_STATUS" <<'PYEOF'
import json, sys, urllib.request, urllib.parse, base64, datetime

sonar_url, project_key, token, report_file, gate_status = sys.argv[1:6]

def get(path, params):
    url = f"{sonar_url}{path}?{urllib.parse.urlencode(params)}"
    req = urllib.request.Request(url)
    auth = base64.b64encode(f"{token}:".encode()).decode()
    req.add_header("Authorization", f"Basic {auth}")
    with urllib.request.urlopen(req) as resp:
        return json.load(resp)

gate = get("/api/qualitygates/project_status", {"projectKey": project_key})
gate_project_status = gate["projectStatus"]["status"]

issues = get("/api/issues/search", {
    "componentKeys": project_key,
    "severities": "BLOCKER,CRITICAL",
    "resolved": "false",
    "ps": "500",
})

measures = get("/api/measures/component", {
    "component": project_key,
    "metricKeys": "coverage,ncloc,bugs,vulnerabilities,code_smells,duplicated_lines_density",
})
measure_by_key = {m["metric"]: m.get("value", "n/a") for m in measures.get("component", {}).get("measures", [])}

lines = []
lines.append(f"# Sonar Quality Report - {project_key}")
lines.append("")
lines.append(f"_Generated {datetime.datetime.now(datetime.timezone.utc).isoformat()} by scripts/quality-gate.sh_")
lines.append("")
overall_pass = gate_project_status == "OK" and int(gate_status) == 0 and issues["total"] == 0
banner = "PASS - deploy not blocked" if overall_pass else "FAIL - deploy blocked"
lines.append(f"## Result: {banner}")
lines.append("")
lines.append(f"- Quality Gate: **{gate_project_status}**")
lines.append(f"- Maven build exit code: **{gate_status}**")
lines.append(f"- Open BLOCKER/CRITICAL issues: **{issues['total']}**")
lines.append("")
lines.append("## Key metrics")
lines.append("")
lines.append("| Metric | Value |")
lines.append("|---|---|")
for key, label in [
    ("coverage", "Coverage"),
    ("ncloc", "Lines of code"),
    ("bugs", "Bugs"),
    ("vulnerabilities", "Vulnerabilities"),
    ("code_smells", "Code smells"),
    ("duplicated_lines_density", "Duplicated lines (%)"),
]:
    lines.append(f"| {label} | {measure_by_key.get(key, 'n/a')} |")
lines.append("")

if issues["total"] > 0:
    lines.append("## Open BLOCKER/CRITICAL issues")
    lines.append("")
    lines.append("| Severity | Rule | Location | Message |")
    lines.append("|---|---|---|---|")
    for issue in issues["issues"]:
        component = issue.get("component", "").split(":")[-1]
        line_no = issue.get("line", "-")
        lines.append(
            f"| {issue['severity']} | {issue['rule']} | {component}:{line_no} | {issue['message']} |"
        )
    lines.append("")
else:
    lines.append("No open BLOCKER or CRITICAL issues.")
    lines.append("")

lines.append(f"Full dashboard: {sonar_url}/dashboard?id={project_key}")
lines.append("")

with open(report_file, "w") as f:
    f.write("\n".join(lines))

print(f"Wrote {report_file}: gate={gate_project_status}, blocker/critical={issues['total']}")
if not overall_pass:
    sys.exit(1)
PYEOF
REPORT_STATUS=$?

echo "==> Report written to ${REPORT_FILE}"
if [ "$GATE_STATUS" -ne 0 ] || [ "$REPORT_STATUS" -ne 0 ]; then
  echo "==> QUALITY GATE FAILED - blocking deploy" >&2
  exit 1
fi
echo "==> Quality gate passed."
