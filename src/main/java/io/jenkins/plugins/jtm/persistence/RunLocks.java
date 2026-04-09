package io.jenkins.plugins.jtm.persistence;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One monitor per run id so all mutations to the same {@link io.jenkins.plugins.jtm.core.domain.TestRun}
 * (UI, quality gate, API, pipeline) serialize and cannot overwrite each other with stale snapshots.
 */
public final class RunLocks {

    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();

    private RunLocks() {}

    public static Object lockFor(String runId) {
        return LOCKS.computeIfAbsent(Objects.requireNonNull(runId), k -> new Object());
    }
}
