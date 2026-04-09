# Release notes — Jenkins Test Management (JTM)

## Unreleased

### Project

- **Maven artifact ID:** Renamed to `jtm` (HPI: `jtm.hpi`) per [Jenkins plugin naming](https://www.jenkins.io/doc/developer/publishing/style-guides/#artifact-id); no prior release was published under the old ID.
- **Repository layout:** The Jenkins plugin Maven project (`pom.xml`, `src/`) now lives at the repository root instead of under `jtm-plugin/`. Build and CI commands run from the clone root.
- **Jenkins hosting / CD prep:** [Incrementals](https://www.jenkins.io/doc/developer/plugin-development/incrementals/) versioning (`${revision}.${changelist}`; local snapshot `1.999999-SNAPSHOT`), `.mvn/maven.config` with `changelist.format`, `.mvn/extensions.xml`, Dependabot (Maven + GitHub Actions), root `Jenkinsfile` for ci.jenkins.io, and GitHub workflows `cd.yaml` plus `jenkins-security-scan.yml` aligned with [plugin hosting](https://www.jenkins.io/projects/infrastructure/) expectations.

### Features

- **Jenkins post-build step: parameter expansion support**
  - Post-build import fields now resolve Jenkins environment/build variables (for example `${version}`) instead of storing literal placeholders.
  - Supported fields include JUnit XML path, target run ID, new run name, and project key.

- **Test Cases UI: bulk selection and delete**
  - Added multi-select checkboxes in the Test Cases list.
  - Added "Select all" and "Delete selected" actions.
  - Added server-side bulk delete endpoint with result feedback (deleted/failed counts).

- **Test Runs UI: bulk delete from selection**
  - Added "Delete selected" action next to multi-run export.
  - Selected runs can now be deleted in one operation from the Test Runs list.

### Bug Fixes

- **JUnit post-build import: reduce duplicate test case creation**
  - Improved existing-case matching for imports without explicit `TC-...` IDs by matching normalized `title + projectKey`.
  - Reuses existing test case IDs when a matching case is found, instead of always creating new ones.

- **Dashboard KPI layout on small screens**
  - Improved KPI grid responsiveness so cards (including "Flaky signals") wrap correctly instead of being pushed off-screen.

- **Run progress dropdown label length**
  - Shortened dropdown entries to show the test run name only (removed job-name prefix) for better readability.

- **Test Run detail: pass rate visibility**
  - Fixed pass-rate rendering in the existing Test Run summary block so the value is reliably displayed.

- **Test Cases filters**
  - Fixed server-side filtering for type and text query in the Test Cases list.
  - Removed last-run status column and status filter from the catalog view (execution status belongs in test runs).

- **Run status recalculation after result updates**
  - Fixed run status transitions so runs are recalculated after adding/updating results and linked scope changes.
  - Prevents runs from incorrectly remaining `PARTIAL` when all test cases are passed.

- **E2E: CSV import test query IDs**
  - Fixed Playwright test URLs to match `e2e/fixtures/jtm-demo.csv` (`TC-001` / `TC-029`), not the separate CSV seed fixture IDs (`TC-CSV-*`).

### Verification

- Targeted test run successful: `mvn -Dtest=JtmImportJUnitRecorderTest test`
- Compile check successful: `mvn -DskipTests compile`
- No linter errors reported for changed files

## 1.0.0

**Jenkins Test Management (JTM)** is a Jenkins plugin for test cases, test runs, quality signals, and pipeline integration. Data is stored under `$JENKINS_HOME/jtm/`.

### Requirements

- Jenkins **2.528.3** or compatible (see `pom.xml`)
- Java **11** to build the plugin

### Highlights

- **Web UI** at `/jtm/` — dashboard, test cases, test runs, run detail with linked-case matrix
- **Project scope** — filter dashboard and lists by project key
- **Pipeline steps** — `publishResults`, `enforceQualityGate`, `updateTestCase`
- **Imports** — CSV / structured text importers; JUnit XML via post-build recorder
- **Exports** — self-contained **HTML** and **PDF** reports for a single test run (optional branding); **multi-run flat export** from the test-run list (combined table with run context)
- **Permissions** — JTM View / Execute / Edit / Admin
- **REST-style API** for automation (see plugin UI)
- **Playwright E2E** scaffolding under `e2e/` (optional; requires a running Jenkins)

### Install

1. Build: `mvn clean package` (from the repository root)
2. Upload `target/jtm.hpi` via **Manage Jenkins → Plugins → Advanced**
3. Grant JTM permissions under **Manage Jenkins → Security**

### Documentation

- [README.md](README.md) — overview and quick start  
- [BUILD.md](BUILD.md) — build, `hpi:run`, permissions, pipeline example, data layout

### License

[MIT](LICENSE)

---

*For GitHub Releases: attach the built `.hpi` to this version instead of committing binaries to the repository.*
