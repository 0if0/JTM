# Contributing

Thanks for your interest in Jenkins Test Management (JTM).

## Development setup

The POM uses Jenkins **incrementals** versioning: `${revision}.${changelist}` (see `pom.xml`). Local builds resolve to **`1.999999-SNAPSHOT`** until you set a release changelist (see [CD of plugins](https://www.jenkins.io/doc/developer/publishing/releasing-cd/)).

1. JDK **17+**, **Maven 3.8+**
2. Clone the repository and build:

   ```bash
   mvn clean verify
   ```

3. For a local Jenkins with the plugin:

   ```bash
   mvn hpi:run
   ```

## Pull requests

- Open a PR against the default branch (`main`).
- Keep changes focused; match existing code style and naming.
- Ensure `mvn clean verify` passes (unit tests, integration tests, and SpotBugs).  
  SpotBugs is configured with `spotbugs.failOnError=true`, so new findings fail the build.

## Project metadata

The canonical repo is [0if0/JTM](https://github.com/0if0/JTM). Forks should update `pom.xml` (`<url>`, `<scm>`) and the CI badge in [README.md](README.md).

## Security

See [SECURITY.md](SECURITY.md). Jenkins plugins follow the project-wide process: report via the **SECURITY** project in [Jenkins Jira](https://issues.jenkins.io/) (details on [jenkins.io/security](https://www.jenkins.io/security/)), not via public GitHub issues.
