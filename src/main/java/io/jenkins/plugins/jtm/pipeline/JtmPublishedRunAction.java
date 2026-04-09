package io.jenkins.plugins.jtm.pipeline;

import hudson.model.InvisibleAction;

/**
 * Attached to a {@link hudson.model.Run} when {@code publishResults} completes,
 * so {@code enforceQualityGate} can resolve the correct run without relying on
 * environment variables or global "latest run" (unsafe under parallel jobs).
 */
public final class JtmPublishedRunAction extends InvisibleAction {

    private static final long serialVersionUID = 1L;

    private final String lastPublishedRunId;

    public JtmPublishedRunAction(String lastPublishedRunId) {
        this.lastPublishedRunId = lastPublishedRunId;
    }

    public String getLastPublishedRunId() {
        return lastPublishedRunId;
    }
}
