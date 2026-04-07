# Contributing

Thanks for your interest in Jenkins Test Management (JTM).

## Development setup

1. JDK **11+** (17 recommended as runtime for Maven), **Maven 3.8+**
2. Clone the repository and build:

   ```bash
   cd jtm-plugin
   mvn clean verify
   ```

3. For a local Jenkins with the plugin:

   ```bash
   mvn hpi:run
   ```

## Pull requests

- Open a PR against the default branch (`main`).
- Keep changes focused; match existing code style and naming.
- Ensure `mvn -f jtm-plugin/pom.xml clean verify` passes (unit tests, integration tests, and SpotBugs).  
  SpotBugs is configured with `spotbugs.failOnError=false` until remaining findings are fixed; please avoid adding new ones.

## Project metadata

The canonical repo is [0if0/JTM](https://github.com/0if0/JTM). Forks should update `jtm-plugin/pom.xml` (`<url>`, `<scm>`) and the CI badge in [README.md](README.md).

## Security

Please report sensitive issues privately to the repository maintainers (use GitHub **Security advisories** if enabled) rather than public issues.
