package io.jenkins.plugins.jtm.export;

import java.io.Serializable;

/** One line in a test run export (case + result + steps summary). */
public final class ExportRow implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String testCaseId;
    private final String title;
    private final String status;
    private final String assignee;
    private final String stepsSummary;

    public ExportRow(String testCaseId, String title, String status, String assignee, String stepsSummary) {
        this.testCaseId = testCaseId;
        this.title = title;
        this.status = status;
        this.assignee = assignee;
        this.stepsSummary = stepsSummary;
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
}
