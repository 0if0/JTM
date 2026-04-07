# Jenkins Test Management (JTM) — Build Instructions

## Prerequisites
- Java 11 or higher: `java -version`
- Maven 3.8+: `mvn -version` (optional: use `jtm-plugin/mvnw` from this directory)
- Network access to repo.jenkins-ci.org

## Quick Build

```bash
# Build the plugin (skip tests for speed)
mvn clean package -DskipTests

# Output: target/jenkins-test-management.hpi  (~2 MB)
```

## Run Locally

```bash
# Start Jenkins with JTM pre-installed on http://localhost:8080
mvn hpi:run
# Then visit: http://localhost:8080/jtm/
```

## Run Tests

```bash
# Full build with integration tests (requires Jenkins test harness download ~5 min first run)
mvn clean test
```

## Install in Production Jenkins

1. Build: `mvn clean package -DskipTests`
2. Open Jenkins → Manage Jenkins → Plugins → Advanced → Deploy Plugin
3. Upload: `target/jenkins-test-management.hpi`
4. Restart Jenkins when prompted
5. Navigate to: `https://your-jenkins/jtm/`

## Configure Permissions

Jenkins → Manage Jenkins → Security → Authorization (Matrix-based security):
- Add rows for each user/group
- Add columns: `JTM/View`, `JTM/Execute`, `JTM/Edit`, `JTM/Admin`

## Pipeline Usage

```groovy
pipeline {
  agent any
  stages {
    stage('Test') {
      steps {
        sh 'mvn test'
        // Generate jtm-results.json from your test runner
      }
      post {
        always {
          script {
            env.JTM_RUN_ID = publishResults(
              resultsFile: 'build/jtm-results.json',
              updateTestCases: true
            )
          }
        }
      }
    }
    stage('Quality Gate') {
      steps {
        enforceQualityGate(minPassRate: 95, blockOnCritical: true)
      }
    }
  }
}
```

## File Layout After Build

```
target/
├── jenkins-test-management.hpi    ← Upload this to Jenkins
└── classes/                       ← Compiled class files
```

## Data Storage

After installation, test data is stored at:
```
$JENKINS_HOME/jtm/
├── testcases/    TC-0001.json, TC-0002.json, ...
├── suites/       SUITE-0001.json, ...
├── runs/         RUN-0001.json, ...
└── audit/        2024-01/audit-2024-01-15.jsonl
```
