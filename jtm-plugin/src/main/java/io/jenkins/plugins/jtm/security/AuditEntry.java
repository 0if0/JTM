package io.jenkins.plugins.jtm.security;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable audit log entry. Records every security-relevant action
 * in JTM. Stored persistently alongside domain objects.
 *
 * <p>Entries are never modified or deleted (append-only log).
 */
public final class AuditEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Action {
        TEST_CASE_CREATED,
        TEST_CASE_UPDATED,
        TEST_CASE_DELETED,
        TEST_CASE_STATUS_CHANGED,
        TEST_SUITE_CREATED,
        TEST_SUITE_DELETED,
        TEST_RUN_STARTED,
        TEST_RUN_FINISHED,
        QUALITY_GATE_EVALUATED,
        PERMISSION_DENIED,
        API_ACCESS,
        CONFIG_CHANGED
    }

    @JsonProperty("id")
    private final String id;

    @JsonProperty("action")
    private final Action action;

    @JsonProperty("entityType")
    private final String entityType;

    @JsonProperty("entityId")
    private final String entityId;

    @JsonProperty("performedBy")
    private final String performedBy;

    @JsonProperty("timestamp")
    private final Instant timestamp;

    @JsonProperty("details")
    private final String details;

    @JsonProperty("ipAddress")
    private final String ipAddress;

    @JsonProperty("buildNumber")
    private final Integer buildNumber;

    @JsonProperty("oldValue")
    private final String oldValue;

    @JsonProperty("newValue")
    private final String newValue;

    private AuditEntry(Builder builder) {
        this.id = Objects.requireNonNull(builder.id);
        this.action = Objects.requireNonNull(builder.action);
        this.entityType = builder.entityType;
        this.entityId = builder.entityId;
        this.performedBy = builder.performedBy;
        this.timestamp = Objects.requireNonNullElse(builder.timestamp, Instant.now());
        this.details = builder.details;
        this.ipAddress = builder.ipAddress;
        this.buildNumber = builder.buildNumber;
        this.oldValue = builder.oldValue;
        this.newValue = builder.newValue;
    }

    public static Builder builder(String id, Action action) {
        return new Builder(id, action);
    }

    public String getId() { return id; }
    public Action getAction() { return action; }
    public String getEntityType() { return entityType; }
    public String getEntityId() { return entityId; }
    public String getPerformedBy() { return performedBy; }
    public Instant getTimestamp() { return timestamp; }
    public String getDetails() { return details; }
    public String getIpAddress() { return ipAddress; }
    public Integer getBuildNumber() { return buildNumber; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }

    @Override
    public String toString() {
        return String.format("[AUDIT] %s | %s | entity=%s(%s) | user=%s | %s",
            timestamp, action, entityType, entityId, performedBy, details);
    }

    public static final class Builder {
        private final String id;
        private final Action action;
        private String entityType;
        private String entityId;
        private String performedBy;
        private Instant timestamp;
        private String details;
        private String ipAddress;
        private Integer buildNumber;
        private String oldValue;
        private String newValue;

        private Builder(String id, Action action) {
            this.id = id;
            this.action = action;
        }

        public Builder entityType(String entityType) { this.entityType = entityType; return this; }
        public Builder entityId(String entityId) { this.entityId = entityId; return this; }
        public Builder performedBy(String user) { this.performedBy = user; return this; }
        public Builder timestamp(Instant ts) { this.timestamp = ts; return this; }
        public Builder details(String details) { this.details = details; return this; }
        public Builder ipAddress(String ip) { this.ipAddress = ip; return this; }
        public Builder buildNumber(Integer bn) { this.buildNumber = bn; return this; }
        public Builder oldValue(String v) { this.oldValue = v; return this; }
        public Builder newValue(String v) { this.newValue = v; return this; }
        public AuditEntry build() { return new AuditEntry(this); }
    }
}
