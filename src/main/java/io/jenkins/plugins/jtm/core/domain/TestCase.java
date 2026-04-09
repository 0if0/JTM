package io.jenkins.plugins.jtm.core.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.lang3.StringUtils;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Core domain entity representing a single test case in JTM.
 *
 * <p>Designed for thread-safe access via defensive copies.
 * All mutation returns new instances to support event sourcing.
 *
 * @author JTM Development Team
 * @since 1.0.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class TestCase implements Serializable {

    private static final long serialVersionUID = 1L;

    // ── Identity ──────────────────────────────────────────────────────────────

    @JsonProperty("id")
    private final String id;

    @JsonProperty("version")
    private final int version;

    @JsonProperty("parentSuiteId")
    private final String parentSuiteId;

    // ── Core Metadata ─────────────────────────────────────────────────────────

    @JsonProperty("title")
    private final String title;

    @JsonProperty("description")
    private final String description;       // Markdown-supported

    @JsonProperty("preconditions")
    private final String preconditions;

    @JsonProperty("expectedResult")
    private final String expectedResult;

    @JsonProperty("steps")
    private final List<TestStep> steps;

    // ── Classification ────────────────────────────────────────────────────────

    @JsonProperty("type")
    private final TestCaseType type;

    @JsonProperty("priority")
    private final Priority priority;

    @JsonProperty("risk")
    private final Risk risk;

    @JsonProperty("tags")
    private final List<String> tags;

    @JsonProperty("lifecycleStatus")
    private final LifecycleStatus lifecycleStatus;

    // ── Traceability ──────────────────────────────────────────────────────────

    @JsonProperty("linkedJob")
    private final String linkedJob;

    @JsonProperty("requirementId")
    private final String requirementId;

    @JsonProperty("jiraTicket")
    private final String jiraTicket;

    /** Logical project scope (filter); empty = unassigned. Not final so Jackson can set when loading old JSON. */
    @JsonProperty("projectKey")
    // lgtm[java] not a credential; logical project scope key
    private String projectKey;

    // ── Audit ─────────────────────────────────────────────────────────────────

    @JsonProperty("createdAt")
    private final Instant createdAt;

    @JsonProperty("updatedAt")
    private final Instant updatedAt;

    @JsonProperty("createdBy")
    private final String createdBy;

    @JsonProperty("updatedBy")
    private final String updatedBy;

    // ── Last Execution ────────────────────────────────────────────────────────

    @JsonProperty("lastStatus")
    private final TestCaseStatus lastStatus;

    @JsonProperty("lastRunAt")
    private final Instant lastRunAt;

    @JsonProperty("lastRunBy")
    private final String lastRunBy;

    @JsonProperty("lastBuildNumber")
    private final Integer lastBuildNumber;

    @JsonProperty("flakyScore")
    private final int flakyScore;  // 0-100, increases with status flips

    // ── Enumerations ──────────────────────────────────────────────────────────

    public enum TestCaseType {
        MANUAL, AUTOMATED, EXPLORATORY, PERFORMANCE, SECURITY
    }

    public enum Priority {
        CRITICAL, HIGH, MEDIUM, LOW
    }

    public enum Risk {
        HIGH, MEDIUM, LOW
    }

    public enum LifecycleStatus {
        DRAFT, READY, BLOCKED, DEPRECATED
    }

    public enum TestCaseStatus {
        PASSED, FAILED, BLOCKED, SKIPPED, FALSE_POSITIVE, PENDING
    }

    // ── Constructor (Jackson) ──────────────────────────────────────────────────
    private TestCase() {
        // for Jackson deserialization only
        this.id = null; this.version = 0; this.parentSuiteId = null;
        this.title = null; this.description = null; this.preconditions = null;
        this.expectedResult = null; this.steps = Collections.emptyList();
        this.type = TestCaseType.MANUAL; this.priority = Priority.MEDIUM;
        this.risk = Risk.MEDIUM; this.tags = Collections.emptyList();
        this.lifecycleStatus = LifecycleStatus.DRAFT;
        this.linkedJob = null; this.requirementId = null; this.jiraTicket = null;
        this.projectKey = "";
        this.createdAt = Instant.now(); this.updatedAt = Instant.now();
        this.createdBy = null; this.updatedBy = null;
        this.lastStatus = TestCaseStatus.PENDING; this.lastRunAt = null;
        this.lastRunBy = null; this.lastBuildNumber = null; this.flakyScore = 0;
    }

    // ── Builder Pattern ───────────────────────────────────────────────────────

    private TestCase(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id must not be null");
        this.version = builder.version;
        this.parentSuiteId = builder.parentSuiteId;
        this.title = Objects.requireNonNull(builder.title, "title must not be null");
        this.description = StringUtils.defaultString(builder.description);
        this.preconditions = StringUtils.defaultString(builder.preconditions);
        this.expectedResult = StringUtils.defaultString(builder.expectedResult);
        this.steps = Collections.unmodifiableList(new ArrayList<>(
            builder.steps != null ? builder.steps : Collections.emptyList()));
        this.type = Objects.requireNonNullElse(builder.type, TestCaseType.MANUAL);
        this.priority = Objects.requireNonNullElse(builder.priority, Priority.MEDIUM);
        this.risk = Objects.requireNonNullElse(builder.risk, Risk.MEDIUM);
        this.tags = Collections.unmodifiableList(new ArrayList<>(
            builder.tags != null ? builder.tags : Collections.emptyList()));
        this.lifecycleStatus = Objects.requireNonNullElse(
            builder.lifecycleStatus, LifecycleStatus.DRAFT);
        this.linkedJob = builder.linkedJob;
        this.requirementId = builder.requirementId;
        this.jiraTicket = builder.jiraTicket;
        this.projectKey = StringUtils.defaultString(builder.projectKey);
        this.createdAt = Objects.requireNonNullElse(builder.createdAt, Instant.now());
        this.updatedAt = Objects.requireNonNullElse(builder.updatedAt, Instant.now());
        this.createdBy = builder.createdBy;
        this.updatedBy = builder.updatedBy;
        this.lastStatus = Objects.requireNonNullElse(
            builder.lastStatus, TestCaseStatus.PENDING);
        this.lastRunAt = builder.lastRunAt;
        this.lastRunBy = builder.lastRunBy;
        this.lastBuildNumber = builder.lastBuildNumber;
        this.flakyScore = Math.max(0, Math.min(100, builder.flakyScore));
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.id = this.id; b.version = this.version; b.parentSuiteId = this.parentSuiteId;
        b.title = this.title; b.description = this.description;
        b.preconditions = this.preconditions; b.expectedResult = this.expectedResult;
        b.steps = new ArrayList<>(this.steps); b.type = this.type;
        b.priority = this.priority; b.risk = this.risk;
        b.tags = new ArrayList<>(this.tags);
        b.lifecycleStatus = this.lifecycleStatus; b.linkedJob = this.linkedJob;
        b.requirementId = this.requirementId; b.jiraTicket = this.jiraTicket;
        b.projectKey = StringUtils.defaultString(this.projectKey);
        b.createdAt = this.createdAt; b.updatedAt = this.updatedAt;
        b.createdBy = this.createdBy; b.updatedBy = this.updatedBy;
        b.lastStatus = this.lastStatus; b.lastRunAt = this.lastRunAt;
        b.lastRunBy = this.lastRunBy; b.lastBuildNumber = this.lastBuildNumber;
        b.flakyScore = this.flakyScore;
        return b;
    }

    // ── Domain Logic ──────────────────────────────────────────────────────────

    /**
     * Returns true if this test is considered "flaky" (high status volatility).
     */
    public boolean isFlaky() {
        return flakyScore >= 40;
    }

    /**
     * Returns true if this test case is executable (lifecycle allows execution).
     */
    public boolean isExecutable() {
        return lifecycleStatus == LifecycleStatus.READY;
    }

    /**
     * Creates a new instance with updated status, bumping version + audit fields.
     */
    public TestCase withStatus(TestCaseStatus newStatus, String updatedBy,
                               int buildNumber, int newFlakyScore) {
        return this.toBuilder()
            .version(this.version + 1)
            .lastStatus(newStatus)
            .lastRunAt(Instant.now())
            .lastRunBy(updatedBy)
            .lastBuildNumber(buildNumber)
            .updatedAt(Instant.now())
            .updatedBy(updatedBy)
            .flakyScore(newFlakyScore)
            .build();
    }

    // ── Getters (immutable) ───────────────────────────────────────────────────

    public String getId() { return id; }
    public int getVersion() { return version; }
    public String getParentSuiteId() { return parentSuiteId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getPreconditions() { return preconditions; }
    public String getExpectedResult() { return expectedResult; }
    public List<TestStep> getSteps() { return steps; }
    public TestCaseType getType() { return type; }
    public Priority getPriority() { return priority; }
    public Risk getRisk() { return risk; }
    public List<String> getTags() { return tags; }
    public LifecycleStatus getLifecycleStatus() { return lifecycleStatus; }
    public String getLinkedJob() { return linkedJob; }
    public String getRequirementId() { return requirementId; }
    public String getJiraTicket() { return jiraTicket; }
    public String getProjectKey() { return StringUtils.defaultString(projectKey); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getCreatedBy() { return createdBy; }
    public String getUpdatedBy() { return updatedBy; }
    public TestCaseStatus getLastStatus() { return lastStatus; }
    public Instant getLastRunAt() { return lastRunAt; }
    public String getLastRunBy() { return lastRunBy; }
    public Integer getLastBuildNumber() { return lastBuildNumber; }
    public int getFlakyScore() { return flakyScore; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TestCase)) return false;
        TestCase tc = (TestCase) o;
        return Objects.equals(id, tc.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return String.format("TestCase{id='%s', title='%s', status=%s, type=%s}",
            id, title, lastStatus, type);
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static final class Builder {
        private String id;
        private int version = 1;
        private String parentSuiteId;
        private String title;
        private String description;
        private String preconditions;
        private String expectedResult;
        private List<TestStep> steps;
        private TestCaseType type = TestCaseType.MANUAL;
        private Priority priority = Priority.MEDIUM;
        private Risk risk = Risk.MEDIUM;
        private List<String> tags;
        private LifecycleStatus lifecycleStatus = LifecycleStatus.DRAFT;
        private String linkedJob;
        private String requirementId;
        private String jiraTicket;
        // lgtm[java] not a credential; logical project scope key
        private String projectKey = "";
        private Instant createdAt;
        private Instant updatedAt;
        private String createdBy;
        private String updatedBy;
        private TestCaseStatus lastStatus = TestCaseStatus.PENDING;
        private Instant lastRunAt;
        private String lastRunBy;
        private Integer lastBuildNumber;
        private int flakyScore = 0;

        private Builder() {}

        public Builder id(String id) { this.id = id; return this; }
        public Builder version(int v) { this.version = v; return this; }
        public Builder parentSuiteId(String s) { this.parentSuiteId = s; return this; }
        public Builder title(String t) { this.title = t; return this; }
        public Builder description(String d) { this.description = d; return this; }
        public Builder preconditions(String p) { this.preconditions = p; return this; }
        public Builder expectedResult(String e) { this.expectedResult = e; return this; }
        public Builder steps(List<TestStep> s) { this.steps = s; return this; }
        public Builder type(TestCaseType t) { this.type = t; return this; }
        public Builder priority(Priority p) { this.priority = p; return this; }
        public Builder risk(Risk r) { this.risk = r; return this; }
        public Builder tags(List<String> t) { this.tags = t; return this; }
        public Builder lifecycleStatus(LifecycleStatus s) { this.lifecycleStatus = s; return this; }
        public Builder linkedJob(String j) { this.linkedJob = j; return this; }
        public Builder requirementId(String r) { this.requirementId = r; return this; }
        public Builder jiraTicket(String j) { this.jiraTicket = j; return this; }
        public Builder projectKey(String p) { this.projectKey = p; return this; }
        public Builder createdAt(Instant i) { this.createdAt = i; return this; }
        public Builder updatedAt(Instant i) { this.updatedAt = i; return this; }
        public Builder createdBy(String u) { this.createdBy = u; return this; }
        public Builder updatedBy(String u) { this.updatedBy = u; return this; }
        public Builder lastStatus(TestCaseStatus s) { this.lastStatus = s; return this; }
        public Builder lastRunAt(Instant i) { this.lastRunAt = i; return this; }
        public Builder lastRunBy(String u) { this.lastRunBy = u; return this; }
        public Builder lastBuildNumber(Integer n) { this.lastBuildNumber = n; return this; }
        public Builder flakyScore(int s) { this.flakyScore = s; return this; }

        public TestCase build() { return new TestCase(this); }
    }
}
