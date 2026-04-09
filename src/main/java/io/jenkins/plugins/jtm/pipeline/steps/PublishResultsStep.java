package io.jenkins.plugins.jtm.pipeline.steps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableSet;
import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.Extension;
import io.jenkins.plugins.jtm.core.domain.*;
import io.jenkins.plugins.jtm.core.service.TestCaseService;
import io.jenkins.plugins.jtm.persistence.JtmStore;
import io.jenkins.plugins.jtm.pipeline.JtmPublishedRunAction;
import org.jenkinsci.plugins.workflow.steps.*;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.time.Instant;
import java.util.*;

/**
 * Pipeline step: {@code publishResults}
 *
 * <p>Parses a JSON test results file and publishes results to JTM.
 * Creates a TestRun record and updates individual TestCase statuses.
 *
 * <h3>JSON Schema for results file:</h3>
 * <pre>{@code
 * {
 *   "runName":    "Sprint 14 Regression",
 *   "release":    "v3.1.0",
 *   "branch":     "release/3.1",
 *   "commitId":   "abc123",
 *   "environment": "staging",
 *   "results": [
 *     {
 *       "testCaseId":  "TC-0001",
 *       "status":      "PASSED",      // PASSED|FAILED|BLOCKED|SKIPPED
 *       "durationMs":  1234,
 *       "comment":     "optional",
 *       "errorMessage": "optional",
 *       "stackTrace":  "optional"
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <h3>Pipeline DSL Usage:</h3>
 * <pre>{@code
 * publishResults resultsFile: 'test-results/jtm-results.json'
 *
 * // With options
 * publishResults(
 *   resultsFile:       'build/jtm-results.json',
 *   updateTestCases:   true,
 *   failOnParseError:  true
 * )
 * }</pre>
 *
 * @author JTM Development Team
 */
public final class PublishResultsStep extends Step implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String resultsFile;
    private boolean updateTestCases = true;
    private boolean failOnParseError = false;
    private boolean createMissingTestCases = false;

    @DataBoundConstructor
    public PublishResultsStep(String resultsFile) {
        if (resultsFile == null || resultsFile.isBlank()) {
            throw new IllegalArgumentException("resultsFile is required");
        }
        this.resultsFile = resultsFile.trim();
    }

    @DataBoundSetter
    public void setUpdateTestCases(boolean updateTestCases) {
        this.updateTestCases = updateTestCases;
    }

    @DataBoundSetter
    public void setFailOnParseError(boolean failOnParseError) {
        this.failOnParseError = failOnParseError;
    }

    @DataBoundSetter
    public void setCreateMissingTestCases(boolean createMissingTestCases) {
        this.createMissingTestCases = createMissingTestCases;
    }

    public String getResultsFile() { return resultsFile; }
    public boolean isUpdateTestCases() { return updateTestCases; }
    public boolean isFailOnParseError() { return failOnParseError; }
    public boolean isCreateMissingTestCases() { return createMissingTestCases; }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new Execution(this, context);
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    public static final class Execution extends SynchronousStepExecution<String> {

        private static final long serialVersionUID = 1L;

        private final PublishResultsStep step;

        protected Execution(PublishResultsStep step, StepContext context) {
            super(context);
            this.step = step;
        }

        @Override
        protected String run() throws Exception {
            TaskListener listener = getContext().get(TaskListener.class);
            Run<?, ?> build = getContext().get(Run.class);
            FilePath workspace = getContext().get(FilePath.class);

            assert listener != null && build != null && workspace != null;
            String logPrefix = "[JTM] publishResults";

            // ── Locate and read results file ────────────────────────────────────
            FilePath resultsFilePath = workspace.child(step.resultsFile);
            if (!resultsFilePath.exists()) {
                String msg = logPrefix + " ✗ Results file not found: " + step.resultsFile;
                if (step.failOnParseError) throw new RuntimeException(msg);
                listener.getLogger().println(msg);
                return null;
            }

            String json = resultsFilePath.readToString();
            ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());

            ResultsPayload payload;
            try {
                payload = mapper.readValue(json, ResultsPayload.class);
            } catch (Exception e) {
                String msg = logPrefix + " ✗ Failed to parse results file: " + e.getMessage();
                if (step.failOnParseError) throw new RuntimeException(msg, e);
                listener.getLogger().println(msg);
                return null;
            }

            // ── Create TestRun ──────────────────────────────────────────────────
            JtmStore store = JtmStore.get();
            TestRun run = new TestRun(
                store.generateRunId(),
                build.getParent().getName(),
                build.getNumber()
            );
            run.setName(payload.runName != null ? payload.runName : run.getName());
            run.setBranch(payload.branch != null ? payload.branch : "unknown");
            run.setCommitId(payload.commitId);
            run.setRelease(payload.release);
            run.setEnvironment(payload.environment);
            run.setTriggeredBy("pipeline:" + build.getParent().getName());
            run.setStartedAt(Instant.now());

            int updated = 0, created = 0, notFound = 0;
            TestCaseService service = TestCaseService.get();

            if (payload.results != null) {
                for (ResultsPayload.ResultEntry entry : payload.results) {
                    if (entry.testCaseId == null) continue;

                    // Parse status
                    TestCaseResult.TestResultStatus status;
                    try {
                        status = TestCaseResult.TestResultStatus.valueOf(
                            entry.status.toUpperCase());
                    } catch (Exception e) {
                        listener.getLogger().println(
                            logPrefix + " ⚠ Unknown status '" + entry.status +
                            "' for " + entry.testCaseId + ", defaulting to FAILED");
                        status = TestCaseResult.TestResultStatus.FAILED;
                    }

                    // Build result
                    TestCaseResult result = new TestCaseResult(
                        entry.testCaseId, status, entry.durationMs);
                    result.setComment(entry.comment);
                    result.setErrorMessage(entry.errorMessage);
                    result.setStackTrace(entry.stackTrace);
                    result.setExecutedBy("pipeline");
                    run.addResult(result);

                    // Update TestCase status
                    if (step.updateTestCases) {
                        boolean exists = service.findById(entry.testCaseId).isPresent();

                        if (exists) {
                            TestCase.TestCaseStatus tcStatus = mapToTcStatus(status);
                            service.updateStatus(entry.testCaseId, tcStatus,
                                "pipeline:" + build.getParent().getName(),
                                build.getNumber(), 8);
                            updated++;
                        } else if (step.createMissingTestCases) {
                            try {
                                service.createTestCaseWithFixedId(
                                    entry.testCaseId,
                                    "Auto: " + entry.testCaseId,
                                    TestCase.TestCaseType.AUTOMATED,
                                    TestCase.Priority.MEDIUM,
                                    "pipeline"
                                );
                                TestCase.TestCaseStatus tcStatus = mapToTcStatus(status);
                                service.updateStatus(entry.testCaseId, tcStatus,
                                    "pipeline:" + build.getParent().getName(),
                                    build.getNumber(), 8);
                                created++;
                            } catch (IllegalArgumentException ex) {
                                listener.getLogger().println(
                                    logPrefix + " ⚠ " + ex.getMessage());
                                notFound++;
                            }
                        } else {
                            notFound++;
                        }
                    }
                }
            }

            run.finish();
            store.saveRun(run);
            build.replaceAction(new JtmPublishedRunAction(run.getId()));

            // ── Summary ─────────────────────────────────────────────────────────
            listener.getLogger().println(logPrefix + " ─────────────────────");
            listener.getLogger().printf(
                "%s ✓ Published %d results to run %s%n", logPrefix,
                run.getTotalCount(), run.getId());
            listener.getLogger().printf(
                "%s   Pass Rate: %.1f%% | Passed: %d | Failed: %d | Blocked: %d%n",
                logPrefix, run.getPassRate(), run.getPassedCount(),
                run.getFailedCount(), run.getBlockedCount());
            listener.getLogger().printf(
                "%s   Updated: %d test cases | Created: %d | Not Found: %d%n",
                logPrefix, updated, created, notFound);

            return run.getId();
        }

        private TestCase.TestCaseStatus mapToTcStatus(TestCaseResult.TestResultStatus s) {
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
    }

    // ── JSON Payload Schema ───────────────────────────────────────────────────

    public static final class ResultsPayload implements Serializable {
        public String runName;
        public String release;
        public String branch;
        public String commitId;
        public String environment;
        public List<ResultEntry> results;

        public static final class ResultEntry implements Serializable {
            public String testCaseId;
            public String status;
            public long durationMs;
            public String comment;
            public String errorMessage;
            public String stackTrace;
        }
    }

    // ── Descriptor ────────────────────────────────────────────────────────────

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return ImmutableSet.of(Run.class, TaskListener.class, FilePath.class);
        }

        @Override
        public String getFunctionName() { return "publishResults"; }

        @Override
        @Nonnull
        public String getDisplayName() { return "JTM: Publish Test Results"; }
    }
}
