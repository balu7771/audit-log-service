# Quality gates: coverage, static analysis, image scanning

Three independent gates, each capable of **blocking a deploy** by exiting
non-zero. None of them are wired into a CI pipeline in this repo (there
isn't one yet — see `docs/PROMPT_HISTORY.md`), but each is a single command
a pipeline (or a developer, pre-push) can run as a pass/fail step.

| Gate | Command | Blocks on |
|---|---|---|
| Code coverage (JaCoCo) | `./mvnw verify` | Line coverage < 80% (config/DTOs excluded) |
| Static analysis (SonarQube) | `./scripts/quality-gate.sh` | Quality Gate failure or any open BLOCKER/CRITICAL issue |
| Container image scan (Trivy) | `./scripts/trivy-scan.sh` | Any CRITICAL/HIGH vulnerability in the built image |

## 1. Code coverage (JaCoCo)

`pom.xml`'s `jacoco-maven-plugin` instruments both unit tests (Surefire) and
integration tests (Failsafe, Testcontainers) into separate exec files
(`target/jacoco-unit.exec`, `target/jacoco-it.exec`), merges them
(`target/jacoco-merged.exec`), then reports and checks against that merged
result in the `verify` phase — so the enforced number reflects the whole
test suite, not just unit tests.

**Config classes and request/response DTOs are excluded from instrumentation
entirely** (`com.persistent.auditlog.config.**`, `*Request`/`*Response`
classes in `api`, the `@SpringBootApplication` entry point) — these are
declarative bindings with no branching logic worth a test asserting on, and
including them would let coverage on the classes that actually matter (the
sixteen HMAC and hash chain integrity, or RBAC's `@PreAuthorize`
enforcement) hide behind a padded aggregate number.

```bash
./mvnw verify                        # fails the build if coverage < 80%
open target/site/jacoco/index.html   # per-class HTML breakdown
```

## 2. Static analysis (SonarQube)

### Bring up SonarQube locally

SonarQube is **not** part of the default `docker compose up` — it needs
~2GB RAM and a cold start that would slow down the everyday app+postgres
loop for no benefit unless you're running an analysis right now. It lives
behind the `quality` compose profile with its own dedicated Postgres
database (not the bundled embedded H2 SonarQube falls back to without one —
H2 is explicitly an eval-only, single-node option upstream; a real database
is the correct choice even for a local instance you intend to keep using
across sessions):

```bash
docker compose --profile quality up -d sonarqube-db sonarqube
# first boot takes ~60-90s; poll:
curl -s http://localhost:9000/api/system/status
```

### Run the analysis and generate the report

```bash
./scripts/quality-gate.sh
```

This script:

1. Ensures SonarQube is up (starts it if not).
2. Bootstraps a Sonar authentication token. **Local-eval-only shortcut**: on
   first run it logs in as `admin`/`admin`, changes the password (Sonar
   forces this before most API calls succeed), and caches the generated
   token in `.sonar-token` (gitignored). Set `SONAR_TOKEN` to skip all of
   this and use a real, pre-provisioned token instead — required for
   anything beyond a throwaway local instance.
3. Runs `./mvnw verify sonar:sonar`, which re-runs the full build so the
   JaCoCo report Sonar ingests (`sonar.coverage.jacoco.xmlReportPaths` in
   `pom.xml`) is current. `sonar.qualitygate.wait=true` (also in `pom.xml`)
   makes this Maven invocation itself **block and fail** if the server-side
   Quality Gate doesn't pass — this is the actual deploy-blocking mechanism,
   not something the script re-implements.
4. Regardless of step 3's outcome, queries the Sonar Web API for the
   Quality Gate status and every open `BLOCKER`/`CRITICAL` issue, and writes
   `docs/reports/sonar-report.md` — checked into the repo and overwritten
   (not appended) on every run, so it always reflects the most recent scan
   of the current branch. Re-run the script after every meaningful change to
   keep it current ("iteratively update").
5. Exits non-zero if the gate failed or any BLOCKER/CRITICAL issue is open.

### Configuring the Quality Gate to actually gate on severity

A fresh SonarQube ships with the built-in "Sonar way" gate, which by default
only evaluates **new code** (this analysis run's diff against a baseline),
not the whole codebase, and doesn't key specifically off
Blocker/Critical severity. Since the ask here is "any Blocker/Critical issue
blocks deploy" — not "no new issues since last week" — create a custom gate
once, in the SonarQube UI (Quality Gates → Create):

- Condition: **Blocker Issues** (on Overall Code) — is greater than `0`
- Condition: **Critical Issues** (on Overall Code) — is greater than `0`

...then set it as default, or assign it to this project specifically
(Project Settings → Quality Gate). `scripts/quality-gate.sh`'s own
BLOCKER/CRITICAL issue count (step 4 above) enforces this same rule
independently of which gate is configured server-side, so the script's exit
code is correct even before you've done this one-time setup — but the
Maven-level `sonar.qualitygate.wait` block (step 3) only reflects whatever
gate is actually configured, so this step is what makes *that* signal
meaningful too.

## 3. Container image scan (Trivy)

```bash
./scripts/trivy-scan.sh
```

Builds `audit-log-service:local` (via `docker compose build app`), scans it
with Trivy for `CRITICAL`/`HIGH` vulnerabilities (base image + JDK + any
dependency Trivy can fingerprint in the built jar), and writes
`docs/reports/trivy-report.json` (raw) and `docs/reports/trivy-report.md`
(summary table) — both checked into the repo, overwritten on each run.
Non-zero exit on any finding.

Trivy runs as a one-shot service under the same `quality` compose profile
(`docker compose --profile quality run --rm trivy`), mounting the host
Docker socket to scan an already-built local image rather than pulling one
from a registry.

## Why none of this is wired into CI yet

There is no CI pipeline in this repository (`docs/PROMPT_HISTORY.md`
confirms this was built as a standalone exercise, not against a hosted CI
provider). Wiring `./mvnw verify`, `./scripts/quality-gate.sh`, and
`./scripts/trivy-scan.sh` into one is a contained follow-up: any CI system
capable of running `docker compose` and treating a non-zero exit code as a
failed step can gate a deploy on all three commands above with no further
changes needed here.
