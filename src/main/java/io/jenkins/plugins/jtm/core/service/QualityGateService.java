package io.jenkins.plugins.jtm.core.service;

import io.jenkins.plugins.jtm.core.domain.TestCase;
import io.jenkins.plugins.jtm.core.domain.TestRun;
import io.jenkins.plugins.jtm.persistence.JtmStore;
import io.jenkins.plugins.jtm.persistence.RunLocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Evaluates Quality Gate conditions for a given test run or job.
 *
 * <p>Conditions checked:
 * <ul>
 *   <li>Minimum pass rate (default 95%)</li>
 *   <li>Maximum allowed failures</li>
 *   <li>No blocked tests (optional)</li>
 *   <li>No critical tests failing</li>
 * </ul>
 *
 * @author JTM Development Team
 */
public final class QualityGateService {

    private static final Logger LOG = Logger.getLogger(QualityGateService.class.getName());

    private static final QualityGateService INSTANCE = new QualityGateService();
    public static QualityGateService get() { return INSTANCE; }

    private final JtmStore store;

    private QualityGateService() {
        this.store = JtmStore.get();
    }

    // ── Gate Evaluation ───────────────────────────────────────────────────────

    /**
     * Evaluates quality gate conditions for a completed test run.
     *
     * @param runId          ID of the TestRun to evaluate
     * @param minPassRate    minimum pass rate (0-100), e.g. 95.0
     * @param maxFailures    maximum allowed failures (-1 = unlimited)
     * @param blockOnBlocked fail gate if any test is BLOCKED
     * @param blockOnCritical fail gate if any CRITICAL test fails
     * @return QualityGateResult with detailed breakdown
     */
    public QualityGateResult evaluate(String runId, double minPassRate, int maxFailures,
                                      boolean blockOnBlocked, boolean blockOnCritical) {
        TestRun run = store.findRunById(runId)
            .orElseThrow(() -> new IllegalArgumentException("TestRun not found: " + runId));

        return evaluateRun(run, minPassRate, maxFailures, blockOnBlocked, blockOnCritical);
    }

    /**
     * Evaluates quality gate directly on a TestRun object.
     */
    public QualityGateResult evaluateRun(TestRun run, double minPassRate, int maxFailures,
                                          boolean blockOnBlocked, boolean blockOnCritical) {
        synchronized (RunLocks.lockFor(run.getId())) {
            // Always re-load the latest run from the store so we never overwrite
            // concurrent UI updates (e.g. step statuses) with a stale TestRun reference.
            TestRun latest = store.findRunById(run.getId())
                .orElseThrow(() -> new IllegalArgumentException("TestRun not found: " + run.getId()));
        List<String> violations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        double passRate = latest.getPassRate();
        long failCount = latest.getFailedCount();
        long blockedCount = latest.getBlockedCount();

        // ── Check 1: Minimum Pass Rate ─────────────────────────────────────────
        if (passRate < minPassRate) {
            violations.add(String.format(
                "Pass rate %.1f%% is below minimum requirement of %.1f%%",
                passRate, minPassRate));
        }

        // ── Check 2: Maximum Failures ──────────────────────────────────────────
        if (maxFailures >= 0 && failCount > maxFailures) {
            violations.add(String.format(
                "%d failed tests exceeds maximum allowed failures of %d",
                failCount, maxFailures));
        }

        // ── Check 3: Blocked Tests ─────────────────────────────────────────────
        if (blockOnBlocked && blockedCount > 0) {
            violations.add(String.format(
                "%d blocked tests found — gate requires zero blocked tests",
                blockedCount));
        } else if (blockedCount > 0) {
            warnings.add(blockedCount + " blocked tests found");
        }

        // ── Check 4: Critical Test Failures ────────────────────────────────────
        if (blockOnCritical) {
            List<String> criticalFailures = getCriticalFailures(latest);
            if (!criticalFailures.isEmpty()) {
                violations.add(String.format(
                    "Critical test(s) failed: %s", String.join(", ", criticalFailures)));
            }
        }

        // ── Check 5: Flaky Tests Warning ───────────────────────────────────────
        long flakyCount = store.getFlakyTests(Integer.MAX_VALUE).stream()
            .filter(tc -> latest.getResultFor(tc.getId()).isPresent()).count();
        if (flakyCount > 0) {
            warnings.add(flakyCount + " flaky test(s) detected in this run");
        }

        boolean passed = violations.isEmpty();

        // Persist result to run
        latest.setQualityGatePassed(passed);
        latest.setQualityGateDetails(buildSummary(passed, violations, warnings, passRate, latest));
        store.saveRun(latest);

        LOG.info(String.format("[JTM] Quality gate for run %s: %s (pass rate: %.1f%%)",
            latest.getId(), passed ? "PASSED" : "FAILED", passRate));

        return new QualityGateResult(passed, passRate, failCount, blockedCount,
            violations, warnings, latest.getId());
        }
    }

    private List<String> getCriticalFailures(TestRun run) {
        List<String> failed = new ArrayList<>();
        run.getResults().stream()
            .filter(r -> r.getStatus() == io.jenkins.plugins.jtm.core.domain.TestCaseResult.TestResultStatus.FAILED)
            .forEach(r -> store.findTestCaseById(r.getTestCaseId()).ifPresent(tc -> {
                if (tc.getPriority() == TestCase.Priority.CRITICAL) {
                    failed.add(tc.getId() + " (" + tc.getTitle() + ")");
                }
            }));
        return failed;
    }

    private String buildSummary(boolean passed, List<String> violations, List<String> warnings,
                                 double passRate, TestRun run) {
        StringBuilder sb = new StringBuilder();
        sb.append(passed ? "✓ QUALITY GATE PASSED" : "✗ QUALITY GATE FAILED").append("\n");
        sb.append(String.format("Pass Rate: %.1f%% | Passed: %d | Failed: %d | Blocked: %d | Total: %d%n",
            passRate, run.getPassedCount(), run.getFailedCount(),
            run.getBlockedCount(), run.getTotalCount()));
        if (!violations.isEmpty()) {
            sb.append("\nViolations:\n");
            violations.forEach(v -> sb.append("  ✗ ").append(v).append("\n"));
        }
        if (!warnings.isEmpty()) {
            sb.append("\nWarnings:\n");
            warnings.forEach(w -> sb.append("  ⚠ ").append(w).append("\n"));
        }
        return sb.toString();
    }

    // ── QualityGateResult ──────────────────────────────────────────────────────

    public static final class QualityGateResult {
        private final boolean passed;
        private final double passRate;
        private final long failedCount;
        private final long blockedCount;
        private final List<String> violations;
        private final List<String> warnings;
        private final String runId;

        public QualityGateResult(boolean passed, double passRate, long failedCount,
                                  long blockedCount, List<String> violations,
                                  List<String> warnings, String runId) {
            this.passed = passed;
            this.passRate = passRate;
            this.failedCount = failedCount;
            this.blockedCount = blockedCount;
            this.violations = List.copyOf(violations);
            this.warnings = List.copyOf(warnings);
            this.runId = runId;
        }

        public boolean isPassed() { return passed; }
        public double getPassRate() { return passRate; }
        public long getFailedCount() { return failedCount; }
        public long getBlockedCount() { return blockedCount; }
        public List<String> getViolations() { return violations; }
        public List<String> getWarnings() { return warnings; }
        public String getRunId() { return runId; }

        public String getSummary() {
            return String.format("%s | passRate=%.1f%% | failed=%d | blocked=%d",
                passed ? "PASSED" : "FAILED", passRate, failedCount, blockedCount);
        }
    }
}
