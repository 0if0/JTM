package io.jenkins.plugins.jtm.core.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Result of executing a single test case within a TestRun.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class TestCaseResult implements Serializable {

    private static final long serialVersionUID = 1L;

    @JsonProperty("testCaseId")
    private String testCaseId;

    @JsonProperty("status")
    private TestResultStatus status;

    @JsonProperty("durationMs")
    private long durationMs;

    @JsonProperty("comment")
    private String comment;

    @JsonProperty("errorMessage")
    private String errorMessage;

    @JsonProperty("stackTrace")
    private String stackTrace;

    @JsonProperty("timestamp")
    private Instant timestamp;

    @JsonProperty("executedBy")
    private String executedBy;

    /** Jenkins user id assigned to execute this case in this run (optional). */
    @JsonProperty("assignedTo")
    private String assignedTo;

    @JsonProperty("attachmentUrl")
    private String attachmentUrl;

    /** Per-step statuses for this test execution (index-aligned to test case steps). */
    @JsonProperty("stepStatuses")
    private List<TestStep.StepStatus> stepStatuses;

    /** Optional notes per step (same indices as {@link #stepStatuses}). */
    @JsonProperty("stepComments")
    private List<String> stepComments;

    public enum TestResultStatus { PASSED, FAILED, BLOCKED, SKIPPED, FALSE_POSITIVE, PENDING }

    public TestCaseResult() {
        this.timestamp = Instant.now();
        this.stepStatuses = new ArrayList<>();
        this.stepComments = new ArrayList<>();
    }

    public TestCaseResult(String testCaseId, TestResultStatus status, long durationMs) {
        this();
        this.testCaseId = Objects.requireNonNull(testCaseId);
        this.status = Objects.requireNonNull(status);
        this.durationMs = durationMs;
    }

    public String getDurationFormatted() {
        if (durationMs < 1000) return durationMs + "ms";
        if (durationMs < 60000) return String.format("%.2fs", durationMs / 1000.0);
        return String.format("%dm %.1fs", durationMs / 60000, (durationMs % 60000) / 1000.0);
    }

    // Getters / Setters
    public String getTestCaseId() { return testCaseId; }
    public void setTestCaseId(String testCaseId) { this.testCaseId = testCaseId; }
    public TestResultStatus getStatus() { return status; }
    public void setStatus(TestResultStatus status) { this.status = status; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getStackTrace() { return stackTrace; }
    public void setStackTrace(String stackTrace) { this.stackTrace = stackTrace; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public String getExecutedBy() { return executedBy; }
    public void setExecutedBy(String executedBy) { this.executedBy = executedBy; }
    public String getAssignedTo() { return assignedTo; }
    public void setAssignedTo(String assignedTo) { this.assignedTo = assignedTo; }
    public String getAttachmentUrl() { return attachmentUrl; }
    public void setAttachmentUrl(String attachmentUrl) { this.attachmentUrl = attachmentUrl; }
    /**
     * Ignored for Jackson so only {@link #stepStatuses} + {@link #setStepStatuses} define the property;
     * a visible getter would duplicate the field and can yield wrong JSON vs. the backing field.
     */
    @JsonIgnore
    public List<TestStep.StepStatus> getStepStatuses() {
        return stepStatuses == null ? Collections.emptyList() : Collections.unmodifiableList(stepStatuses);
    }
    public void setStepStatuses(List<TestStep.StepStatus> stepStatuses) {
        this.stepStatuses = stepStatuses == null ? new ArrayList<>() : new ArrayList<>(stepStatuses);
    }

    @JsonIgnore
    public List<String> getStepComments() {
        return stepComments == null ? Collections.emptyList() : Collections.unmodifiableList(stepComments);
    }

    public void setStepComments(List<String> stepComments) {
        this.stepComments = stepComments == null ? new ArrayList<>() : new ArrayList<>(stepComments);
    }
}
