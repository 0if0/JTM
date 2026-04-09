package io.jenkins.plugins.jtm.core.service;

import io.jenkins.plugins.jtm.core.domain.TestCase;
import io.jenkins.plugins.jtm.core.domain.TestCaseResult;
import io.jenkins.plugins.jtm.core.domain.TestRun;
import io.jenkins.plugins.jtm.persistence.JtmStore;
import io.jenkins.plugins.jtm.persistence.RunLocks;
import io.jenkins.plugins.jtm.security.AuditEntry;
import io.jenkins.plugins.jtm.security.JtmPermissions;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Service managing the lifecycle of TestRun objects.
 *
 * <p>Handles run creation, result publishing, and finalization.
 * All operations enforce appropriate permissions.
 *
 * @author JTM Development Team
 */
public final class TestRunService {

    private static final Logger LOG = Logger.getLogger(TestRunService.class.getName());
    private static final TestRunService INSTANCE = new TestRunService();

    private static Object lockForRun(String runId) {
        return RunLocks.lockFor(runId);
    }

    public static TestRunService get() { return INSTANCE; }

    private final JtmStore store;
    private final TestCaseService testCaseService;

    private TestRunService() {
        this.store = JtmStore.get();
        this.testCaseService = TestCaseService.get();
    }

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Creates and starts a new test run.
     */
    public TestRun startRun(String jobName, int buildNumber, String branch,
                             String commitId, String release, String environment,
                             String triggeredBy) {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EXECUTE);

        String id = store.generateRunId();
        TestRun run = new TestRun(id, jobName, buildNumber);
        run.setBranch(branch);
        run.setCommitId(commitId);
        run.setRelease(release);
        run.setEnvironment(environment);
        run.setTriggeredBy(triggeredBy);
        run.setStartedAt(Instant.now());

        synchronized (lockForRun(id)) {
            store.saveRun(run);
        }
        audit(AuditEntry.Action.TEST_RUN_STARTED, id, triggeredBy,
            "Started: " + jobName + " #" + buildNumber);

        LOG.info("[JTM] Started test run " + id + " for " + jobName + " #" + buildNumber);
        return run;
    }

    /**
     * Ad-hoc run from the UI (no pipeline). Finished immediately with {@link TestRun.RunStatus#PARTIAL}
     * until results are added; display name defaults to {@code jobName + " #" + buildNumber} when {@code displayName} is null.
     *
     * @param linkedTestCaseIds test cases in scope for this run (e.g. release); seeded as {@code PENDING} results.
     */
    public TestRun createAdHocRun(String displayName, String jobName, int buildNumber,
                                  String branch, String notes, List<String> linkedTestCaseIds, String user,
                                  String projectKey) {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EXECUTE);
        String id = store.generateRunId();
        TestRun run = new TestRun(id, jobName, buildNumber);
        if (displayName != null && !displayName.isBlank()) {
            run.setName(displayName.trim());
        }
        run.setBranch(branch != null ? branch : "");
        run.setProjectKey(StringUtils.trimToEmpty(projectKey));
        run.setTriggeredBy(user);
        run.setNotes(notes);
        run.setLinkedTestCaseIds(linkedTestCaseIds != null ? linkedTestCaseIds : new ArrayList<>());
        Instant now = Instant.now();
        run.setStartedAt(now);
        run.setFinishedAt(now);
        run.setStatus(TestRun.RunStatus.PARTIAL);
        synchronized (lockForRun(id)) {
            store.saveRun(run);

            List<TestCaseResult> seeds = new ArrayList<>();
            for (String tcId : run.getLinkedTestCaseIds()) {
                TestCaseResult r = new TestCaseResult(tcId, TestCaseResult.TestResultStatus.PENDING, 0L);
                r.setExecutedBy(user);
                seeds.add(r);
            }
            if (!seeds.isEmpty()) {
                addResults(run.getId(), seeds, false, 0);
            }
        }

        audit(AuditEntry.Action.TEST_RUN_STARTED, id, user, "Ad-hoc run: " + run.getName());
        LOG.info("[JTM] Ad-hoc test run " + id + ": " + run.getName());
        return getByIdOrThrow(id);
    }

    /**
     * Adds test cases to an existing run’s scope and seeds {@code PENDING} results for new IDs.
     */
    public TestRun appendLinkedTestCases(String runId, List<String> newIds, String user) {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EXECUTE);
        synchronized (lockForRun(runId)) {
            TestRun run = getByIdOrThrow(runId);
            List<TestCaseResult> seeds = new ArrayList<>();
            for (String raw : newIds) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String tcId = raw.trim();
                if (run.getLinkedTestCaseIds().contains(tcId)) {
                    continue;
                }
                run.addLinkedTestCaseId(tcId);
                TestCaseResult r = new TestCaseResult(tcId, TestCaseResult.TestResultStatus.PENDING, 0L);
                r.setExecutedBy(user);
                seeds.add(r);
            }
            recalculateRunStatus(run);
            store.saveRun(run);
            if (!seeds.isEmpty()) {
                addResults(runId, seeds, false, 0);
            }
            return getByIdOrThrow(runId);
        }
    }

    /**
     * Removes a test case from a run’s linked scope and deletes its result for this run.
     */
    public TestRun removeLinkedTestCase(String runId, String testCaseId, String user) {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EXECUTE);
        synchronized (lockForRun(runId)) {
            TestRun run = getByIdOrThrow(runId);
            run.removeLinkedTestCaseId(testCaseId);
            recalculateRunStatus(run);
            store.saveRun(run);
        }
        audit(AuditEntry.Action.TEST_RUN_FINISHED, runId, user,
            "Unlinked test case from run: " + testCaseId);
        return getByIdOrThrow(runId);
    }

    /**
     * Permanently deletes a test run (UI or automation).
     */
    public void deleteRun(String runId, String user) {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EXECUTE);
        synchronized (lockForRun(runId)) {
            if (!store.deleteRun(runId)) {
                throw new NoSuchElementException("TestRun not found: " + runId);
            }
        }
        audit(AuditEntry.Action.TEST_RUN_FINISHED, runId, user, "Deleted test run " + runId);
        LOG.info("[JTM] Deleted test run " + runId);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    public Optional<TestRun> findById(String id) {
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        return store.findRunById(id);
    }

    public TestRun getByIdOrThrow(String id) {
        return findById(id).orElseThrow(() ->
            new NoSuchElementException("TestRun not found: " + id));
    }

    public List<TestRun> findRecent(int limit) {
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        return store.findRecentRuns(limit);
    }

    public List<TestRun> findPaginated(int page, int pageSize, String jobFilter) {
        JtmPermissions.checkPermission(JtmPermissions.TEST_VIEW);
        if (page < 0) page = 0;
        if (pageSize < 1 || pageSize > 100) pageSize = 20;
        return store.findRunsPaginated(page, pageSize, jobFilter);
    }

    // ── Add Results ───────────────────────────────────────────────────────────

    /**
     * Adds a single result to an active run and optionally syncs the TestCase status.
     */
    public TestRun addResult(String runId, TestCaseResult result, boolean updateTestCase) {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EXECUTE);
        synchronized (lockForRun(runId)) {
            TestRun run = getByIdOrThrow(runId);

            run.addResult(result);
            recalculateRunStatus(run);
            store.saveRun(run);

            if (updateTestCase) {
                syncTestCaseStatus(result, 0, 5);
            }

            return run;
        }
    }

    /**
     * Adds a batch of results to a run, optionally syncing test case statuses.
     */
    public TestRun addResults(String runId, List<TestCaseResult> results,
                               boolean updateTestCases, int buildNumber) {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EXECUTE);
        synchronized (lockForRun(runId)) {
            TestRun run = getByIdOrThrow(runId);

            for (TestCaseResult result : results) {
                run.addResult(result);
                if (updateTestCases) {
                    syncTestCaseStatus(result, buildNumber, 8);
                }
            }

            recalculateRunStatus(run);
            store.saveRun(run);
            return run;
        }
    }

    // ── Finalize ──────────────────────────────────────────────────────────────

    /**
     * Finalizes a run — sets status, records finishedAt, persists.
     */
    public TestRun finishRun(String runId, String user) {
        JtmPermissions.checkPermission(JtmPermissions.TEST_EXECUTE);
        TestRun run;
        synchronized (lockForRun(runId)) {
            run = getByIdOrThrow(runId);

            run.finish();
            store.saveRun(run);
        }

        audit(AuditEntry.Action.TEST_RUN_FINISHED, runId, user,
            String.format("Finished: passRate=%.1f%%, failed=%d, blocked=%d",
                run.getPassRate(), run.getFailedCount(), run.getBlockedCount()));

        LOG.info(String.format("[JTM] Finished run %s: passRate=%.1f%%",
            runId, run.getPassRate()));

        return run;
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private void syncTestCaseStatus(TestCaseResult result, int buildNumber, int flakyIncrease) {
        try {
            TestCase.TestCaseStatus newStatus = mapStatus(result.getStatus());
            testCaseService.updateStatus(
                result.getTestCaseId(), newStatus,
                result.getExecutedBy() != null ? result.getExecutedBy() : "pipeline",
                buildNumber, flakyIncrease
            );
        } catch (NoSuchElementException e) {
            LOG.warning("[JTM] TestCase not found during sync: " + result.getTestCaseId());
        } catch (Exception e) {
            LOG.warning("[JTM] Failed to sync TestCase " + result.getTestCaseId() + ": " + e.getMessage());
        }
    }

    private TestCase.TestCaseStatus mapStatus(TestCaseResult.TestResultStatus s) {
        switch (s) {
            case PASSED:  return TestCase.TestCaseStatus.PASSED;
            case FAILED:  return TestCase.TestCaseStatus.FAILED;
            case BLOCKED: return TestCase.TestCaseStatus.BLOCKED;
            case SKIPPED: return TestCase.TestCaseStatus.SKIPPED;
            case FALSE_POSITIVE: return TestCase.TestCaseStatus.FALSE_POSITIVE;
            case PENDING: return TestCase.TestCaseStatus.PENDING;
            default:      return TestCase.TestCaseStatus.PENDING;
        }
    }

    private void recalculateRunStatus(TestRun run) {
        long failed = run.getFailedCount();
        long blocked = run.getBlockedCount();
        long pendingLinked = run.getPendingLinkedCount();
        long total = run.getTotalCount();
        if (failed > 0 || blocked > 0) {
            run.setStatus(TestRun.RunStatus.FAILED);
            return;
        }
        if (pendingLinked > 0) {
            run.setStatus(TestRun.RunStatus.PARTIAL);
            return;
        }
        boolean hasPending = run.getResults().stream()
            .anyMatch(r -> r.getStatus() == TestCaseResult.TestResultStatus.PENDING);
        if (hasPending || total == 0) {
            run.setStatus(TestRun.RunStatus.PARTIAL);
            return;
        }
        run.setStatus(TestRun.RunStatus.PASSED);
    }

    private void audit(AuditEntry.Action action, String runId, String user, String details) {
        store.appendAuditEntry(
            AuditEntry.builder(UUID.randomUUID().toString(), action)
                .entityType("TestRun")
                .entityId(runId)
                .performedBy(user)
                .details(details)
                .build()
        );
    }
}
