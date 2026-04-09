package io.jenkins.plugins.jtm.postbuild;

import hudson.Extension;
import hudson.EnvVars;
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
import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.jtm.core.domain.TestCase;
import io.jenkins.plugins.jtm.core.domain.TestCaseResult;
import io.jenkins.plugins.jtm.core.domain.TestRun;
import io.jenkins.plugins.jtm.core.service.TestCaseService;
import io.jenkins.plugins.jtm.core.service.TestRunService;
import io.jenkins.plugins.jtm.persistence.JtmStore;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import jenkins.tasks.SimpleBuildStep;

import java.io.InputStream;
import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Freestyle/Post-build action: import JUnit XML into a JTM run.
 */
public final class JtmImportJUnitRecorder extends Notifier implements SimpleBuildStep, Serializable {

    private static final long serialVersionUID = 1L;

    private final String junitFile;
    private final String runId;
    private final String runName;
    private String projectScope;
    private final boolean createMissingTestCases;

    @DataBoundConstructor
    public JtmImportJUnitRecorder(String junitFile, String runId, String runName,
                                  String projectScope, boolean createMissingTestCases) {
        this.junitFile = StringUtils.defaultString(junitFile).trim();
        this.runId = StringUtils.defaultString(runId).trim();
        this.runName = StringUtils.defaultString(runName).trim();
        this.projectScope = StringUtils.defaultString(projectScope).trim();
        this.createMissingTestCases = createMissingTestCases;
    }

    /**
     * Legacy {@code &lt;projectKey&gt;} from job {@code config.xml}; merged when {@code projectScope} is blank.
     */
    @Deprecated
    @DataBoundSetter
    public void setProjectKey(String legacy) {
        if (StringUtils.isBlank(projectScope) && StringUtils.isNotBlank(legacy)) {
            projectScope = legacy.trim();
        }
    }

    public String getJunitFile() { return junitFile; }
    public String getRunId() { return runId; }
    public String getRunName() { return runName; }
    public String getProjectScope() { return projectScope; }
    public boolean isCreateMissingTestCases() { return createMissingTestCases; }

    @Override
    public boolean perform(@NonNull AbstractBuild<?, ?> build, @NonNull Launcher launcher, @NonNull BuildListener listener) {
        return performOnRun(build, build.getWorkspace(), listener);
    }

    @Override
    public void perform(@NonNull Run<?, ?> run, @NonNull FilePath workspace, @NonNull Launcher launcher, @NonNull TaskListener listener) {
        performOnRun(run, workspace, listener);
    }

    private boolean performOnRun(Run<?, ?> build, FilePath workspace, TaskListener listener) {
        String logPrefix = "[JTM] importJUnit";
        String resolvedJunitFile = resolveBuildVariables(build, listener, junitFile);
        String resolvedRunId = resolveBuildVariables(build, listener, runId);
        String resolvedRunName = resolveBuildVariables(build, listener, runName);
        String resolvedProjectScope = resolveBuildVariables(build, listener, projectScope);

        if (StringUtils.isBlank(resolvedJunitFile)) {
            listener.error(logPrefix + " junitFile is required");
            build.setResult(Result.UNSTABLE);
            return true;
        }
        if (workspace == null) {
            listener.error(logPrefix + " workspace unavailable");
            build.setResult(Result.UNSTABLE);
            return true;
        }

        FilePath xml = resolveJUnitFile(workspace, resolvedJunitFile);
        if (xml == null) {
            listener.error(logPrefix + " JUnit file not found: " + resolvedJunitFile);
            build.setResult(Result.UNSTABLE);
            return true;
        }
        try {
            if (!xml.exists()) {
                listener.error(logPrefix + " JUnit file not found: " + resolvedJunitFile);
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
            listener.getLogger().println(logPrefix + " No testcase entries found in " + resolvedJunitFile);
            return true;
        }

        TestCaseService testCaseService = TestCaseService.get();
        int createdCases = 0;
        // When JUnit doesn't provide a JTM id (no "TC-..." in testcase name), we generate
        // continuous ids (TC-0001, TC-0002, ...) instead of deriving the id from the name.
        // Also, titles come from the junit testcase "name" directly (no "Auto from JUnit: " prefix).
        Map<String, String> sequentialIdsByCaseIdentifier = new java.util.LinkedHashMap<>();
        Map<String, String> existingByTitleAndProject = buildExistingByTitleAndProject(testCaseService);
        for (JUnitXmlImportParser.ImportedCase c : parsed.getCases()) {
            if (c.isExplicitIdProvided()) {
                continue;
            }
            String titleComposite = compositeTitleKey(c.getDisplayTitle(), c.getProjectScope());
            String existingId = existingByTitleAndProject.get(titleComposite);
            if (StringUtils.isNotBlank(existingId)) {
                c.getResult().setTestCaseId(existingId);
                continue;
            }
            String newId = sequentialIdsByCaseIdentifier.computeIfAbsent(
                c.getCaseIdentifier(), k -> JtmStore.get().generateTestCaseId()
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
                        c.getProjectScope()
                    );
                    existingByTitleAndProject.put(compositeTitleKey(c.getDisplayTitle(), c.getProjectScope()), r.getTestCaseId());
                    createdCases++;
                } catch (Exception ignored) {
                    // keep going
                }
            }
        }

        // Resolve the target run only after ids were remapped, so linkedTestCaseIds match the results.
        TestRun targetRun;
        try {
            targetRun = resolveRun(build, parsed, resolvedRunId, resolvedRunName, resolvedProjectScope, resolvedJunitFile);
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

    private String resolveBuildVariables(Run<?, ?> build, TaskListener listener, String value) {
        if (StringUtils.isBlank(value)) {
            return value;
        }
        try {
            EnvVars env = build.getEnvironment(listener);
            return env.expand(value);
        } catch (Exception e) {
            return value;
        }
    }

    private FilePath resolveJUnitFile(FilePath workspace, String resolvedJunitFile) {
        try {
            if (resolvedJunitFile.contains("*") || resolvedJunitFile.contains("?")) {
                FilePath[] matches = workspace.list(resolvedJunitFile);
                if (matches.length == 0) {
                    return null;
                }
                return matches[0];
            }
            return workspace.child(resolvedJunitFile);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, String> buildExistingByTitleAndProject(TestCaseService testCaseService) {
        Map<String, String> byTitleProject = new HashMap<>();
        for (TestCase tc : testCaseService.findAll()) {
            byTitleProject.putIfAbsent(compositeTitleKey(tc.getTitle(), tc.getProjectScope()), tc.getId());
        }
        return byTitleProject;
    }

    private String compositeTitleKey(String title, String projectScope) {
        String normalizedTitle = StringUtils.trimToEmpty(title).toLowerCase(Locale.ROOT);
        String normalizedProject = StringUtils.trimToEmpty(projectScope).toLowerCase(Locale.ROOT);
        return normalizedProject + "::" + normalizedTitle;
    }

    private TestRun resolveRun(Run<?, ?> build, JUnitXmlImportParser.ParseResult parsed,
                               String resolvedRunId, String resolvedRunName,
                               String resolvedProjectScope, String resolvedJunitFile) {
        if (StringUtils.isNotBlank(resolvedRunId)) {
            return TestRunService.get().getByIdOrThrow(resolvedRunId);
        }
        String jobName = build.getParent().getName();
        int buildNum = build.getNumber();
        String branch = "";
        String notes = "Imported from JUnit file: " + resolvedJunitFile;
        String display = StringUtils.isNotBlank(resolvedRunName) ? resolvedRunName : ("JUnit Import — " + jobName + " #" + buildNum);
        // Use <testsuite name="..."> as project scope for the imported run.
        String projectScopeForRun = parsed.getProjectScope();
        if (StringUtils.isBlank(projectScopeForRun)) {
            projectScopeForRun = resolvedProjectScope;
        }
        TestRun run = TestRunService.get().createAdHocRun(
            display, jobName, buildNum, branch, notes,
            new ArrayList<>(parsed.getLinkedCaseIds()), "postbuild", projectScopeForRun
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

        @NonNull
        @Override
        public String getDisplayName() {
            return "JTM: Import JUnit results into test run";
        }
    }

}
