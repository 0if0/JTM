package io.jenkins.plugins.jtm.postbuild;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import io.jenkins.plugins.jtm.core.domain.TestCase;
import io.jenkins.plugins.jtm.core.domain.TestCaseResult;
import io.jenkins.plugins.jtm.core.domain.TestRun;
import io.jenkins.plugins.jtm.core.service.TestCaseService;
import io.jenkins.plugins.jtm.core.service.TestRunService;
import io.jenkins.plugins.jtm.persistence.JtmStore;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import jenkins.tasks.SimpleBuildStep;

import javax.annotation.Nonnull;
import java.io.InputStream;
import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Freestyle/Post-build action: import JUnit XML into a JTM run.
 */
public final class JtmImportJUnitRecorder extends Notifier implements SimpleBuildStep, Serializable {

    private static final long serialVersionUID = 1L;

    private final String junitFile;
    private final String runId;
    private final String runName;
    private final String projectKey;
    private final boolean createMissingTestCases;

    @DataBoundConstructor
    public JtmImportJUnitRecorder(String junitFile, String runId, String runName,
                                  String projectKey, boolean createMissingTestCases) {
        this.junitFile = StringUtils.defaultString(junitFile).trim();
        this.runId = StringUtils.defaultString(runId).trim();
        this.runName = StringUtils.defaultString(runName).trim();
        this.projectKey = StringUtils.defaultString(projectKey).trim();
        this.createMissingTestCases = createMissingTestCases;
    }

    public String getJunitFile() { return junitFile; }
    public String getRunId() { return runId; }
    public String getRunName() { return runName; }
    public String getProjectKey() { return projectKey; }
    public boolean isCreateMissingTestCases() { return createMissingTestCases; }

    @Override
    public boolean perform(@Nonnull AbstractBuild<?, ?> build, @Nonnull Launcher launcher, @Nonnull BuildListener listener) {
        return performOnRun(build, build.getWorkspace(), listener);
    }

    @Override
    public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull TaskListener listener) {
        performOnRun(run, workspace, listener);
    }

    private boolean performOnRun(Run<?, ?> build, FilePath workspace, TaskListener listener) {
        String logPrefix = "[JTM] importJUnit";
        if (StringUtils.isBlank(junitFile)) {
            listener.error(logPrefix + " junitFile is required");
            build.setResult(Result.UNSTABLE);
            return true;
        }
        if (workspace == null) {
            listener.error(logPrefix + " workspace unavailable");
            build.setResult(Result.UNSTABLE);
            return true;
        }

        FilePath xml = resolveJUnitFile(workspace);
        if (xml == null) {
            listener.error(logPrefix + " JUnit file not found: " + junitFile);
            build.setResult(Result.UNSTABLE);
            return true;
        }
        try {
            if (!xml.exists()) {
                listener.error(logPrefix + " JUnit file not found: " + junitFile);
                build.setResult(Result.UNSTABLE);
                return true;
            }
        } catch (Exception e) {
            listener.error(logPrefix + " Could not access JUnit file: " + e.getMessage());
            build.setResult(Result.UNSTABLE);
            return true;
        }

        JUnitXmlImportParser.ParseResult parsed;
        try (InputStream in = xml.read()) {
            parsed = JUnitXmlImportParser.parse(in);
        } catch (Exception e) {
            listener.error(logPrefix + " Could not parse JUnit XML: " + e.getMessage());
            build.setResult(Result.UNSTABLE);
            return true;
        }

        if (parsed.getResults().isEmpty()) {
            listener.getLogger().println(logPrefix + " No testcase entries found in " + junitFile);
            return true;
        }

        TestCaseService testCaseService = TestCaseService.get();
        int createdCases = 0;
        // When JUnit doesn't provide a JTM id (no "TC-..." in testcase name), we generate
        // continuous ids (TC-0001, TC-0002, ...) instead of deriving the id from the name.
        // Also, titles come from the junit testcase "name" directly (no "Auto from JUnit: " prefix).
        java.util.Map<String, String> sequentialIdsByCaseKey = new java.util.LinkedHashMap<>();
        for (JUnitXmlImportParser.ImportedCase c : parsed.getCases()) {
            if (c.isExplicitIdProvided()) {
                continue;
            }
            String newId = sequentialIdsByCaseKey.computeIfAbsent(
                c.getCaseKey(), k -> JtmStore.get().generateTestCaseId()
            );
            c.getResult().setTestCaseId(newId);
        }

        for (JUnitXmlImportParser.ImportedCase c : parsed.getCases()) {
            TestCaseResult r = c.getResult();
            Optional<TestCase> existing = testCaseService.findById(r.getTestCaseId());
            if (existing.isEmpty() && createMissingTestCases) {
                try {
                    testCaseService.createTestCaseWithFixedId(
                        r.getTestCaseId(),
                        c.getDisplayTitle(),
                        TestCase.TestCaseType.AUTOMATED,
                        TestCase.Priority.MEDIUM,
                        "postbuild",
                        c.getProjectKey()
                    );
                    createdCases++;
                } catch (Exception ignored) {
                    // keep going
                }
            }
        }

        // Resolve the target run only after ids were remapped, so linkedTestCaseIds match the results.
        TestRun targetRun;
        try {
            targetRun = resolveRun(build, parsed);
        } catch (Exception e) {
            listener.error(logPrefix + " Could not resolve target run: " + e.getMessage());
            build.setResult(Result.UNSTABLE);
            return true;
        }

        TestRunService runService = TestRunService.get();
        if (!parsed.getLinkedCaseIds().isEmpty()) {
            runService.appendLinkedTestCases(targetRun.getId(), new ArrayList<>(parsed.getLinkedCaseIds()), "postbuild");
        }
        for (TestCaseResult r : parsed.getResults()) {
            runService.addResult(targetRun.getId(), r, true);
        }

        listener.getLogger().println(logPrefix + " Imported " + parsed.getResults().size()
            + " results into run " + targetRun.getId() + " (created cases: " + createdCases + ")");
        return true;
    }

    private FilePath resolveJUnitFile(FilePath workspace) {
        try {
            if (junitFile.contains("*") || junitFile.contains("?")) {
                FilePath[] matches = workspace.list(junitFile);
                if (matches.length == 0) {
                    return null;
                }
                return matches[0];
            }
            return workspace.child(junitFile);
        } catch (Exception e) {
            return null;
        }
    }

    private TestRun resolveRun(Run<?, ?> build, JUnitXmlImportParser.ParseResult parsed) {
        if (StringUtils.isNotBlank(runId)) {
            return TestRunService.get().getByIdOrThrow(runId);
        }
        String jobName = build.getParent().getName();
        int buildNum = build.getNumber();
        String branch = "";
        String notes = "Imported from JUnit file: " + junitFile;
        String display = StringUtils.isNotBlank(runName) ? runName : ("JUnit Import — " + jobName + " #" + buildNum);
        // Use <testsuite name="..."> as project key for the imported run.
        String projectKeyForRun = parsed.getProjectKey();
        if (StringUtils.isBlank(projectKeyForRun)) {
            projectKeyForRun = projectKey;
        }
        TestRun run = TestRunService.get().createAdHocRun(
            display, jobName, buildNum, branch, notes,
            new ArrayList<>(parsed.getLinkedCaseIds()), "postbuild", projectKeyForRun
        );
        run.setStartedAt(Instant.now());
        JtmStore.get().saveRun(run);
        return run;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return "JTM: Import JUnit results into test run";
        }
    }

}
