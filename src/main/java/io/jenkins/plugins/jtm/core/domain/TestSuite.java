package io.jenkins.plugins.jtm.core.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Hierarchical container for test cases — forms a tree structure.
 * A suite can contain both test cases and child suites (folder model).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class TestSuite implements Serializable {

    private static final long serialVersionUID = 1L;

    @JsonProperty("id")
    private String id;

    @JsonProperty("n")
    private String name;

    @JsonProperty("description")
    private String description;

    @JsonProperty("parentId")
    private String parentId;   // null = root suite

    @JsonProperty("childSuiteIds")
    private List<String> childSuiteIds;

    @JsonProperty("testCaseIds")
    private List<String> testCaseIds;

    @JsonProperty("tags")
    private List<String> tags;

    @JsonProperty("createdAt")
    private Instant createdAt;

    @JsonProperty("updatedAt")
    private Instant updatedAt;

    @JsonProperty("createdBy")
    private String createdBy;

    @JsonProperty("sortOrder")
    private int sortOrder;

    public TestSuite() {
        this.childSuiteIds = new ArrayList<>();
        this.testCaseIds = new ArrayList<>();
        this.tags = new ArrayList<>();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public TestSuite(String id, String name, String parentId) {
        this();
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
        this.parentId = parentId;
    }

    public boolean isRoot() { return parentId == null; }

    public void addTestCase(String testCaseId) {
        if (!testCaseIds.contains(testCaseId)) testCaseIds.add(testCaseId);
        this.updatedAt = Instant.now();
    }

    public void addChildSuite(String suiteId) {
        if (!childSuiteIds.contains(suiteId)) childSuiteIds.add(suiteId);
        this.updatedAt = Instant.now();
    }

    public void removeTestCase(String testCaseId) {
        testCaseIds.remove(testCaseId);
        this.updatedAt = Instant.now();
    }

    public int getTotalCount() { return testCaseIds.size(); }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }
    public List<String> getChildSuiteIds() { return Collections.unmodifiableList(childSuiteIds); }
    public void setChildSuiteIds(List<String> ids) { this.childSuiteIds = new ArrayList<>(ids); }
    public List<String> getTestCaseIds() { return Collections.unmodifiableList(testCaseIds); }
    public void setTestCaseIds(List<String> ids) { this.testCaseIds = new ArrayList<>(ids); }
    public List<String> getTags() { return Collections.unmodifiableList(tags); }
    public void setTags(List<String> tags) { this.tags = new ArrayList<>(tags); }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
