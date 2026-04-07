package io.jenkins.plugins.jtm.export;

import java.io.Serializable;

/**
 * One row in a multi-run (flat) export: run context plus the same case fields as {@link ExportRow}.
 */
public final class FlatExportRow implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String runId;
    private final String runName;
    private final String jobName;
    private final int buildNumber;
    private final String projectKey;
    private final String testCaseId;
    private final String title;
    private final String status;
    private final String assignee;
    private final String stepsSummary;

    public FlatExportRow(
        String runId,
        String runName,
        String jobName,
        int buildNumber,
        String projectKey,
        String testCaseId,
        String title,
        String status,
        String assignee,
        String stepsSummary
    ) {
        this.runId = runId;
        this.runName = runName;
        this.jobName = jobName;
        this.buildNumber = buildNumber;
        this.projectKey = projectKey;
        this.testCaseId = testCaseId;
        this.title = title;
        this.status = status;
        this.assignee = assignee;
        this.stepsSummary = stepsSummary;
    }

    public String getRunId() {
        return runId;
    }

    public String getRunName() {
        return runName;
    }

    public String getJobName() {
        return jobName;
    }

    public int getBuildNumber() {
        return buildNumber;
    }

    public String getProjectKey() {
        return projectKey;
    }

    public String getTestCaseId() {
        return testCaseId;
    }

    public String getTitle() {
        return title;
    }

    public String getStatus() {
        return status;
    }

    public String getAssignee() {
        return assignee;
    }

    public String getStepsSummary() {
        return stepsSummary;
    }

    public String getJobBuildLabel() {
        return jobName + " #" + buildNumber;
    }
}
