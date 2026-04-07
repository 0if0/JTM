package io.jenkins.plugins.jtm.core.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Represents a single test execution run — created per Jenkins build
 * or triggered manually. Contains all individual test case results.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class TestRun implements Serializable {

    private static final long serialVersionUID = 1L;

    // ── Identity ──────────────────────────────────────────────────────────────
    @JsonProperty("id")
    private String id;

    @JsonProperty("n")
    private String name;

    // ── Jenkins Context ───────────────────────────────────────────────────────
    @JsonProperty("buildNumber")
    private int buildNumber;

    @JsonProperty("jobName")
    private String jobName;

    @JsonProperty("branch")
    private String branch;

    @JsonProperty("commitId")
    private String commitId;

    @JsonProperty("release")
    private String release;

    @JsonProperty("environment")
    private String environment;

    /** Logical project scope; empty = unassigned. */
    @JsonProperty("projectKey")
    private String projectKey;

    // ── Run Metadata ──────────────────────────────────────────────────────────
    @JsonProperty("status")
    private RunStatus status;

    @JsonProperty("triggeredBy")
    private String triggeredBy;

    @JsonProperty("startedAt")
    private Instant startedAt;

    @JsonProperty("finishedAt")
    private Instant finishedAt;

    @JsonProperty("results")
    private List<TestCaseResult> results;

    @JsonProperty("notes")
    private String notes;

    // ── Quality Gate ──────────────────────────────────────────────────────────
    @JsonProperty("qualityGatePassed")
    private Boolean qualityGatePassed;

    @JsonProperty("qualityGateDetails")
    private String qualityGateDetails;

    /** Test cases planned for this run (e.g. release scope). Results use the same IDs. */
    @JsonProperty("linkedTestCaseIds")
    private List<String> linkedTestCaseIds;

    // ── Enums ─────────────────────────────────────────────────────────────────
    public enum RunStatus { RUNNING, PASSED, FAILED, ABORTED, PARTIAL }

    public TestRun() {
        this.results = new ArrayList<>();
        this.linkedTestCaseIds = new ArrayList<>();
        this.projectKey = "";
        this.startedAt = Instant.now();
        this.status = RunStatus.RUNNING;
    }

    public TestRun(String id, String jobName, int buildNumber) {
        this();
        this.id = Objects.requireNonNull(id);
        this.jobName = Objects.requireNonNull(jobName);
        this.buildNumber = buildNumber;
        this.name = jobName + " #" + buildNumber;
    }

    // ── Domain Logic ──────────────────────────────────────────────────────────

    public void finish() {
        this.finishedAt = Instant.now();
        long failed = countByStatus(TestCaseResult.TestResultStatus.FAILED);
        long passed = countByStatus(TestCaseResult.TestResultStatus.PASSED);
        long falsePositive = countByStatus(TestCaseResult.TestResultStatus.FALSE_POSITIVE);
        this.status = (failed == 0 && (passed > 0 || falsePositive > 0)) ? RunStatus.PASSED : RunStatus.FAILED;
    }

    public double getPassRate() {
        if (linkedTestCaseIds != null && !linkedTestCaseIds.isEmpty()) {
            long ok = linkedTestCaseIds.stream()
                .filter(id -> getResultFor(id)
                    .map(r -> {
                        TestCaseResult.TestResultStatus s = r.getStatus();
                        return s == TestCaseResult.TestResultStatus.PASSED
                            || s == TestCaseResult.TestResultStatus.FALSE_POSITIVE;
                    })
                    .orElse(false))
                .count();
            return (double) ok / linkedTestCaseIds.size() * 100.0;
        }
        if (results.isEmpty()) return 0.0;
        long ok = results.stream()
            .filter(r -> r.getStatus() == TestCaseResult.TestResultStatus.PASSED
                || r.getStatus() == TestCaseResult.TestResultStatus.FALSE_POSITIVE)
            .count();
        return (double) ok / results.size() * 100.0;
    }

    /** Results still {@link TestCaseResult.TestResultStatus#PENDING} among linked IDs. */
    public long getPendingLinkedCount() {
        if (linkedTestCaseIds == null || linkedTestCaseIds.isEmpty()) {
            return 0L;
        }
        return linkedTestCaseIds.stream()
            .filter(id -> getResultFor(id)
                .map(r -> r.getStatus() == TestCaseResult.TestResultStatus.PENDING)
                .orElse(true))
            .count();
    }

    public long getDurationMs() {
        if (startedAt == null) return 0;
        Instant end = finishedAt != null ? finishedAt : Instant.now();
        return end.toEpochMilli() - startedAt.toEpochMilli();
    }

    public String getDurationFormatted() {
        long ms = getDurationMs();
        if (ms < 1000) return ms + "ms";
        if (ms < 60000) return String.format("%.1fs", ms / 1000.0);
        return String.format("%dm %ds", ms / 60000, (ms % 60000) / 1000);
    }

    private long countByStatus(TestCaseResult.TestResultStatus status) {
        return results.stream().filter(r -> r.getStatus() == status).count();
    }

    public long getPassedCount() { return countByStatus(TestCaseResult.TestResultStatus.PASSED); }
    public long getFailedCount() { return countByStatus(TestCaseResult.TestResultStatus.FAILED); }
    public long getBlockedCount() { return countByStatus(TestCaseResult.TestResultStatus.BLOCKED); }
    public long getSkippedCount() { return countByStatus(TestCaseResult.TestResultStatus.SKIPPED); }
    public long getFalsePositiveCount() { return countByStatus(TestCaseResult.TestResultStatus.FALSE_POSITIVE); }

    public Optional<TestCaseResult> getResultFor(String testCaseId) {
        return results.stream().filter(r -> r.getTestCaseId().equals(testCaseId)).findFirst();
    }

    public void addResult(TestCaseResult result) {
        results.removeIf(r -> r.getTestCaseId().equals(result.getTestCaseId()));
        results.add(Objects.requireNonNull(result));
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getBuildNumber() { return buildNumber; }
    public void setBuildNumber(int buildNumber) { this.buildNumber = buildNumber; }
    public String getJobName() { return jobName; }
    public void setJobName(String jobName) { this.jobName = jobName; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public String getCommitId() { return commitId; }
    public void setCommitId(String commitId) { this.commitId = commitId; }
    public String getRelease() { return release; }
    public void setRelease(String release) { this.release = release; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getProjectKey() { return projectKey != null ? projectKey : ""; }
    public void setProjectKey(String projectKey) { this.projectKey = projectKey != null ? projectKey.trim() : ""; }
    public RunStatus getStatus() { return status; }
    public void setStatus(RunStatus status) { this.status = status; }
    public String getTriggeredBy() { return triggeredBy; }
    public void setTriggeredBy(String triggeredBy) { this.triggeredBy = triggeredBy; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    /** Short locale date/time for dashboards (avoids raw {@link Instant#toString()} in Jelly). */
    public String getStartedAtDisplay() {
        if (startedAt == null) {
            return "—";
        }
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(startedAt);
    }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public List<TestCaseResult> getResults() { return Collections.unmodifiableList(results); }
    public void setResults(List<TestCaseResult> results) { this.results = new ArrayList<>(results); }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Boolean getQualityGatePassed() { return qualityGatePassed; }
    public void setQualityGatePassed(Boolean qualityGatePassed) { this.qualityGatePassed = qualityGatePassed; }
    public String getQualityGateDetails() { return qualityGateDetails; }
    public void setQualityGateDetails(String qualityGateDetails) { this.qualityGateDetails = qualityGateDetails; }
    public int getTotalCount() { return results.size(); }

    public List<String> getLinkedTestCaseIds() {
        return linkedTestCaseIds == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(linkedTestCaseIds);
    }

    /** Jelly-friendly count for linked scope (avoid {@code linkedTestCaseIds.size()} in views). */
    public int getLinkedTestCaseCount() {
        return linkedTestCaseIds == null ? 0 : linkedTestCaseIds.size();
    }

    public void setLinkedTestCaseIds(List<String> linkedTestCaseIds) {
        this.linkedTestCaseIds = linkedTestCaseIds == null
            ? new ArrayList<>()
            : new ArrayList<>(new LinkedHashSet<>(linkedTestCaseIds));
    }

    public void addLinkedTestCaseId(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        if (linkedTestCaseIds == null) {
            linkedTestCaseIds = new ArrayList<>();
        }
        String t = id.trim();
        if (!linkedTestCaseIds.contains(t)) {
            linkedTestCaseIds.add(t);
        }
    }

    /** Removes a test case from this run’s linked scope and drops its result row. */
    public void removeLinkedTestCaseId(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        String t = id.trim();
        if (linkedTestCaseIds != null) {
            linkedTestCaseIds.remove(t);
        }
        results.removeIf(r -> t.equals(r.getTestCaseId()));
    }
}
