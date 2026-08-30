#!/usr/bin/env bash
# Builds the app image and scans it with Trivy for CRITICAL/HIGH vulnerabilities,
# writing both the raw JSON and a markdown summary to docs/reports/. Exits non-zero
# (blocking deploy) if any CRITICAL/HIGH finding is present - same "blocked for
# further deploy" contract as scripts/quality-gate.sh, for the image supply chain
# rather than source code. See docs/architecture/QUALITY-GATES.md.
#
# Usage: ./scripts/trivy-scan.sh
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

IMAGE="audit-log-service:local"
REPORT_JSON="docs/reports/trivy-report.json"
REPORT_MD="docs/reports/trivy-report.md"

mkdir -p docs/reports

echo "==> Building ${IMAGE}..."
docker compose build app

echo "==> Scanning ${IMAGE} with Trivy (CRITICAL,HIGH)..."
SCAN_STATUS=0
docker compose --profile quality run --rm trivy || SCAN_STATUS=$?

python3 - "$REPORT_JSON" "$REPORT_MD" "$IMAGE" "$SCAN_STATUS" <<'PYEOF'
import json, sys, datetime

report_json, report_md, image, scan_status = sys.argv[1:5]

with open(report_json) as f:
    data = json.load(f)

findings = []
for result in data.get("Results", []):
    for vuln in result.get("Vulnerabilities", []) or []:
        findings.append({
            "target": result.get("Target", ""),
            "severity": vuln.get("Severity", ""),
            "id": vuln.get("VulnerabilityID", ""),
            "pkg": vuln.get("PkgName", ""),
            "installed": vuln.get("InstalledVersion", ""),
            "fixed": vuln.get("FixedVersion", "n/a"),
            "title": vuln.get("Title", vuln.get("Description", ""))[:120],
        })

findings.sort(key=lambda f: {"CRITICAL": 0, "HIGH": 1}.get(f["severity"], 2))

lines = []
lines.append(f"# Trivy Image Scan Report - {image}")
lines.append("")
lines.append(f"_Generated {datetime.datetime.now(datetime.timezone.utc).isoformat()} by scripts/trivy-scan.sh_")
lines.append("")
banner = "PASS - deploy not blocked" if int(scan_status) == 0 else "FAIL - deploy blocked"
lines.append(f"## Result: {banner}")
lines.append("")
lines.append(f"- CRITICAL/HIGH findings: **{len(findings)}**")
lines.append("")

if findings:
    lines.append("| Severity | Vulnerability | Package | Installed | Fixed in | Target |")
    lines.append("|---|---|---|---|---|---|")
    for f in findings:
        lines.append(
            f"| {f['severity']} | {f['id']} | {f['pkg']} | {f['installed']} | {f['fixed']} | {f['target']} |"
        )
    lines.append("")
else:
    lines.append("No CRITICAL or HIGH vulnerabilities found.")
    lines.append("")

with open(report_md, "w") as out:
    out.write("\n".join(lines))

print(f"Wrote {report_md}: {len(findings)} CRITICAL/HIGH findings")
PYEOF

echo "==> Report written to ${REPORT_MD} (raw JSON: ${REPORT_JSON})"
if [ "$SCAN_STATUS" -ne 0 ]; then
  echo "==> TRIVY SCAN FAILED - blocking deploy" >&2
  exit 1
fi
echo "==> Trivy scan passed."
