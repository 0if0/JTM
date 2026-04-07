package io.jenkins.plugins.jtm.pipeline.steps;

import com.google.common.collect.ImmutableSet;
import hudson.Extension;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.jtm.core.service.QualityGateService;
import io.jenkins.plugins.jtm.pipeline.JtmPublishedRunAction;
import org.jenkinsci.plugins.workflow.steps.*;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Set;

/**
 * Pipeline step: {@code enforceQualityGate}
 *
 * <p>Evaluates configured quality gate conditions for the most recent
 * test run associated with this build. Optionally fails the build.
 *
 * <h3>Pipeline DSL Usage:</h3>
 * <pre>{@code
 * // Standard usage — fails build if gate fails
 * enforceQualityGate minPassRate: 95
 *
 * // Full configuration
 * enforceQualityGate(
 *   runId:           env.JTM_RUN_ID,  // specific run (optional)
 *   minPassRate:     95.0,
 *   maxFailures:     0,
 *   blockOnBlocked:  false,
 *   blockOnCritical: true,
 *   blockOnFailure:  true    // whether to actually fail the build
 * )
 *
 * // Non-blocking evaluation (report only)
 * enforceQualityGate(minPassRate: 80, blockOnFailure: false)
 * }</pre>
 *
 * @author JTM Development Team
 */
public final class EnforceQualityGateStep extends Step implements Serializable {

    private static final long serialVersionUID = 1L;

    private String runId;                      // null = auto-detect from build
    private double minPassRate = 95.0;
    private int maxFailures = -1;              // -1 = unlimited
    private boolean blockOnBlocked = false;
    private boolean blockOnCritical = true;
    private boolean blockOnFailure = true;     // whether to set build FAILURE

    @DataBoundConstructor
    public EnforceQualityGateStep() {}

    @DataBoundSetter public void setRunId(String runId) { this.runId = runId; }
    @DataBoundSetter public void setMinPassRate(double minPassRate) { this.minPassRate = minPassRate; }
    @DataBoundSetter public void setMaxFailures(int maxFailures) { this.maxFailures = maxFailures; }
    @DataBoundSetter public void setBlockOnBlocked(boolean blockOnBlocked) { this.blockOnBlocked = blockOnBlocked; }
    @DataBoundSetter public void setBlockOnCritical(boolean blockOnCritical) { this.blockOnCritical = blockOnCritical; }
    @DataBoundSetter public void setBlockOnFailure(boolean blockOnFailure) { this.blockOnFailure = blockOnFailure; }

    public String getRunId() { return runId; }
    public double getMinPassRate() { return minPassRate; }
    public int getMaxFailures() { return maxFailures; }
    public boolean isBlockOnBlocked() { return blockOnBlocked; }
    public boolean isBlockOnCritical() { return blockOnCritical; }
    public boolean isBlockOnFailure() { return blockOnFailure; }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new Execution(this, context);
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    public static final class Execution extends SynchronousStepExecution<Boolean> {

        private static final long serialVersionUID = 1L;
        private final EnforceQualityGateStep step;

        protected Execution(EnforceQualityGateStep step, StepContext context) {
            super(context);
            this.step = step;
        }

        @Override
        protected Boolean run() throws Exception {
            TaskListener listener = getContext().get(TaskListener.class);
            Run<?, ?> build = getContext().get(Run.class);

            assert listener != null && build != null;
            String logPrefix = "[JTM] enforceQualityGate";

            // Determine run ID: explicit > same-build publishResults > env > global fallback
            String runId = step.runId;
            if (runId == null || runId.isBlank()) {
                JtmPublishedRunAction published = build.getAction(JtmPublishedRunAction.class);
                if (published != null && published.getLastPublishedRunId() != null
                    && !published.getLastPublishedRunId().isBlank()) {
                    runId = published.getLastPublishedRunId();
                }
            }
            if (runId == null || runId.isBlank()) {
                runId = build.getEnvironment(listener).get("JTM_RUN_ID");
            }
            if (runId == null || runId.isBlank()) {
                listener.getLogger().println(
                    logPrefix + " ⚠ No run id on this build and no JTM_RUN_ID — using latest run in store (unsafe if jobs run in parallel)");
                java.util.List<io.jenkins.plugins.jtm.core.domain.TestRun> recentRuns =
                    io.jenkins.plugins.jtm.persistence.JtmStore.get().findRecentRuns(1);
                if (recentRuns.isEmpty()) {
                    listener.getLogger().println(
                        logPrefix + " ⚠ No test run found — skipping quality gate");
                    return true;
                }
                runId = recentRuns.get(0).getId();
            }

            listener.getLogger().println(logPrefix + " Evaluating run: " + runId);
            listener.getLogger().printf(
                logPrefix + " Config: minPassRate=%.1f%%, maxFailures=%d," +
                " blockOnBlocked=%s, blockOnCritical=%s%n",
                step.minPassRate, step.maxFailures,
                step.blockOnBlocked, step.blockOnCritical);

            // Evaluate
            QualityGateService.QualityGateResult result =
                QualityGateService.get().evaluate(
                    runId, step.minPassRate, step.maxFailures,
                    step.blockOnBlocked, step.blockOnCritical);

            // Print detailed results
            listener.getLogger().println(logPrefix + " ──────────────────────────────────");
            if (result.isPassed()) {
                listener.getLogger().printf(
                    logPrefix + " ✓ QUALITY GATE PASSED%n" +
                    logPrefix + "   Pass Rate: %.1f%% | Failed: %d | Blocked: %d%n",
                    result.getPassRate(), result.getFailedCount(), result.getBlockedCount());
            } else {
                listener.getLogger().printf(
                    logPrefix + " ✗ QUALITY GATE FAILED%n" +
                    logPrefix + "   Pass Rate: %.1f%% | Failed: %d | Blocked: %d%n",
                    result.getPassRate(), result.getFailedCount(), result.getBlockedCount());
                result.getViolations().forEach(v ->
                    listener.getLogger().println(logPrefix + "   ✗ VIOLATION: " + v));
            }
            result.getWarnings().forEach(w ->
                listener.getLogger().println(logPrefix + "   ⚠ WARNING: " + w));
            listener.getLogger().println(logPrefix + " ──────────────────────────────────");

            // Fail build if configured
            if (!result.isPassed() && step.blockOnFailure) {
                build.setResult(Result.FAILURE);
                throw new RuntimeException(
                    "Quality Gate FAILED: " + result.getSummary() + "\n" +
                    "See JTM dashboard for details: " +
                    build.getAbsoluteUrl() + "jtm/runs/" + runId);
            }

            return result.isPassed();
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
        public String getFunctionName() { return "enforceQualityGate"; }

        @Override
        @Nonnull
        public String getDisplayName() { return "JTM: Enforce Quality Gate"; }
    }
}
