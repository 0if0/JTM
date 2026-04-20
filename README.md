# Jenkins Test Management (JTM)

**Repository:** [github.com/0if0/JTM](https://github.com/0if0/JTM)

[![CI](https://github.com/0if0/JTM/actions/workflows/ci.yml/badge.svg)](https://github.com/0if0/JTM/actions/workflows/ci.yml)

Lightweight **test management inside Jenkins**: test cases, suites, test runs, quality gates, pipeline steps, HTML/PDF exports, and optional JUnit import. Data lives under `$JENKINS_HOME/jtm/`.

## Features

- **Web UI** at `/jtm/` — dashboard, test cases, test runs, per-run detail and matrix
- **Pipeline steps** — `publishResults`, `enforceQualityGate`, `updateTestCase`
- **Imports** — CSV / structured text, JUnit XML (post-build recorder)
- **Reports** — single-run and **multi-run flat export** (HTML + PDF) with optional branding
- **Permissions** — View / Execute / Edit / Admin (matrix-friendly)
- **REST-style API** for automation (see plugin UI and `JtmApiAction`)

## Screenshots

**Dashboard** — project scope, quality overview KPIs, and insights.

![JTM dashboard — quality overview](docs/images/dashboard-quality-overview.png)

**Charts and recent activity** — status mix, failure trend over time, recent test runs, and run progress.

![JTM dashboard — status mix, trends, and runs](docs/images/dashboard-panels.png)

**Run progress** — pick a run, see outcome counts and pass-rate donut for that build.

![JTM run progress panel](docs/images/run-progress.png)

## Requirements

- Jenkins **2.528.3** or compatible (see `pom.xml`)
- **Java 17+** for building the plugin

## Quick start

### Build the plugin

```bash
mvn clean verify
# HPI: target/jtm.hpi
```

### Run Jenkins locally with the plugin

```bash
mvn hpi:run
# Open http://localhost:8080/jtm/
```

### Install on a real controller

1. Build `target/jtm.hpi`
2. **Manage Jenkins → Plugins → Advanced** → upload the HPI → restart if prompted
3. Grant **JTM** permissions under **Manage Jenkins → Security**

More detail: **[BUILD.md](BUILD.md)** (permissions, pipeline example, data layout).

## Repository layout

| Path | Purpose |
|------|---------|
| `pom.xml`, `src/` | Jenkins plugin (Maven project at repository root, `.hpi` in `target/`) |
| `docs/images/` | README screenshots |
| `e2e/` | Playwright tests against a running Jenkins (optional CI) |
| `scripts/` | Helper scripts used by GitLab deploy jobs (optional) |

## End-to-end tests (`e2e/`)

Optional. Requires a reachable Jenkins with JTM installed and credentials.

```bash
cd e2e
npm ci
npx playwright test
```

Set `JENKINS_BASE_URL` (and optionally `JENKINS_USER` / `JENKINS_PASSWORD`) as in `e2e/playwright.config.ts`.

## Continuous integration

- **GitHub Actions:** [.github/workflows/ci.yml](.github/workflows/ci.yml) — `mvn verify` on a JDK matrix (21/25), plus optional Playwright smoke if Jenkins secrets are configured
- **GitLab CI:** [.gitlab-ci.yml](.gitlab-ci.yml) — build, optional deploy to Jenkins, optional Playwright (if you still use GitLab)

`mvn verify` runs SpotBugs from the Jenkins plugin parent and fails the build on findings (`spotbugs.failOnError=true`).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE) (see also `pom.xml`).

## Publishing metadata

`pom.xml` lists this repository as **home** (`<url>` and `<scm>`). If you fork to another GitHub org or user, update those entries and the CI badge above to match.
