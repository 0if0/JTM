package io.jenkins.plugins.jtm.pipeline.steps;

import com.google.common.collect.ImmutableSet;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.jtm.core.domain.TestCase;
import io.jenkins.plugins.jtm.core.service.TestCaseService;
import edu.umd.cs.findbugs.annotations.NonNull;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.steps.*;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.io.Serializable;
import java.util.Set;

/**
 * Pipeline step: {@code updateTestCase}
 *
 * <p>Updates the status of a test case from within a Jenkins Pipeline.
 * Used for both automated synchronization and manual status overrides.
 *
 * <h3>Pipeline DSL Usage:</h3>
 * <pre>{@code
 * // Minimal usage
 * updateTestCase testCaseId: 'TC-0001', status: 'PASSED'
 *
 * // Full usage
 * updateTestCase(
 *   testCaseId:  'TC-0042',
 *   status:      'FAILED',
 *   comment:     'Assertion error on line 47',
 *   durationMs:  1234
 * )
 *
 * // Inside testManagement namespace (via JtmDsl)
 * testManagement.updateTestCase(
 *   testCaseId: 'TC-101',
 *   status:     'PASSED',
 *   comment:    'Automated execution'
 * )
 * }</pre>
 *
 * @author JTM Development Team
 */
public final class UpdateTestCaseStep extends Step implements Serializable {

    private static final long serialVersionUID = 1L;

    // ── Required Parameters ───────────────────────────────────────────────────

    private final String testCaseId;
    private final String status;

    // ── Optional Parameters ───────────────────────────────────────────────────

    private String comment;
    private long durationMs = -1;
    private boolean failOnNotFound = false;

    @DataBoundConstructor
    public UpdateTestCaseStep(String testCaseId, String status) {
        if (testCaseId == null || testCaseId.isBlank()) {
            throw new IllegalArgumentException("testCaseId is required");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status is required");
        }
        this.testCaseId = testCaseId.trim();
        this.status = status.trim().toUpperCase();
    }

    // ── DataBound Setters ─────────────────────────────────────────────────────

    @DataBoundSetter
    public void setComment(String comment) { this.comment = comment; }

    @DataBoundSetter
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    @DataBoundSetter
    public void setFailOnNotFound(boolean failOnNotFound) {
        this.failOnNotFound = failOnNotFound;
    }

    // ── Step Execution ────────────────────────────────────────────────────────

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new Execution(this, context);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getTestCaseId() { return testCaseId; }
    public String getStatus() { return status; }
    public String getComment() { return comment; }
    public long getDurationMs() { return durationMs; }
    public boolean isFailOnNotFound() { return failOnNotFound; }

    // ── Execution ─────────────────────────────────────────────────────────────

    public static final class Execution extends SynchronousStepExecution<Void> {

        private static final long serialVersionUID = 1L;

        private final UpdateTestCaseStep step;

        protected Execution(UpdateTestCaseStep step, StepContext context) {
            super(context);
            this.step = step;
        }

        @Override
        protected Void run() throws Exception {
            TaskListener listener = getContext().get(TaskListener.class);
            Run<?, ?> run = getContext().get(Run.class);

            assert listener != null;
            assert run != null;

            String logPrefix = "[JTM] updateTestCase";
            listener.getLogger().println(
                logPrefix + " → " + step.testCaseId + " = " + step.status);

            // Parse status
            TestCase.TestCaseStatus newStatus;
            try {
                newStatus = TestCase.TestCaseStatus.valueOf(step.status);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                    "Invalid status '" + step.status + "'. Valid values: " +
                    java.util.Arrays.toString(TestCase.TestCaseStatus.values()));
            }

            // Execute update
            TestCaseService service = TestCaseService.get();
            String user = "pipeline:" + run.getParent().getName();

            try {
                // Calculate flaky increase based on previous status
                TestCase existing = service.findById(step.testCaseId).orElse(null);
                int flakyIncrease = 0;

                if (existing != null && existing.getLastStatus() != null
                    && existing.getLastStatus() != newStatus
                    && existing.getLastStatus() != TestCase.TestCaseStatus.PENDING) {
                    // Status flipped — increase flaky score
                    flakyIncrease = 8;
                }

                TestCase updated = service.updateStatus(
                    step.testCaseId, newStatus, user,
                    run.getNumber(), flakyIncrease);

                listener.getLogger().println(
                    logPrefix + " ✓ Updated " + step.testCaseId +
                    " → " + newStatus + " (v" + updated.getVersion() + ")");

                if (step.comment != null && !step.comment.isBlank()) {
                    listener.getLogger().println(
                        logPrefix + "   Comment: " + step.comment);
                }

                if (updated.isFlaky()) {
                    listener.getLogger().println(
                        logPrefix + " ⚠ Warning: " + step.testCaseId +
                        " is marked as FLAKY (score=" + updated.getFlakyScore() + ")");
                }

            } catch (java.util.NoSuchElementException e) {
                String msg = logPrefix + " ✗ TestCase not found: " + step.testCaseId;
                if (step.failOnNotFound) {
                    throw new RuntimeException(msg, e);
                } else {
                    listener.getLogger().println(msg + " (skipping — failOnNotFound=false)");
                }
            }

            return null;
        }
    }

    // ── Descriptor ────────────────────────────────────────────────────────────

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return ImmutableSet.of(Run.class, TaskListener.class);
        }

        @Override
        public String getFunctionName() {
            return "updateTestCase";
        }

        @Override
        @NonNull
        public String getDisplayName() {
            return "JTM: Update Test Case Status";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return false;
        }
    }
}
