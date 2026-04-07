package io.jenkins.plugins.jtm.core.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

// ══════════════════════════════════════════════════════════════════════════════
// TestStep — Individual step within a manual test case
// ══════════════════════════════════════════════════════════════════════════════

@JsonIgnoreProperties(ignoreUnknown = true)
public final class TestStep implements Serializable {

    private static final long serialVersionUID = 1L;

    @JsonProperty("orderIndex")
    private int orderIndex;

    @JsonProperty("action")
    private String action;

    @JsonProperty("expectedResult")
    private String expectedResult;

    @JsonProperty("actualResult")
    private String actualResult;

    @JsonProperty("status")
    private StepStatus status;

    @JsonProperty("attachmentUrl")
    private String attachmentUrl;

    public enum StepStatus { NOT_RUN, PASSED, FAILED, BLOCKED, FALSE_POSITIVE }

    public TestStep() { this.status = StepStatus.NOT_RUN; }

    public TestStep(int orderIndex, String action, String expectedResult) {
        this.orderIndex = orderIndex;
        this.action = Objects.requireNonNull(action);
        this.expectedResult = expectedResult;
        this.status = StepStatus.NOT_RUN;
    }

    public int getOrderIndex() { return orderIndex; }
    public void setOrderIndex(int orderIndex) { this.orderIndex = orderIndex; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getExpectedResult() { return expectedResult; }
    public void setExpectedResult(String expectedResult) { this.expectedResult = expectedResult; }
    public String getActualResult() { return actualResult; }
    public void setActualResult(String actualResult) { this.actualResult = actualResult; }
    public StepStatus getStatus() {
        return status != null ? status : StepStatus.NOT_RUN;
    }

    public void setStatus(StepStatus status) {
        this.status = status != null ? status : StepStatus.NOT_RUN;
    }
    public String getAttachmentUrl() { return attachmentUrl; }
    public void setAttachmentUrl(String attachmentUrl) { this.attachmentUrl = attachmentUrl; }
}
