package com.logss.main.analysis;

import com.logss.main.store.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spans of one trace arrive over several batches and the root span arrives last. A trace is analyzed
 * once its root has been in for {@link #ROOT_GRACE_MS}, or after {@link #QUIET_TIMEOUT_MS} without new spans.
 */
@Component
public class TraceFinalizer {
    private static final Logger log = LoggerFactory.getLogger(TraceFinalizer.class);
    static final long ROOT_GRACE_MS = 2_000;
    static final long QUIET_TIMEOUT_MS = 30_000;
    static final int MAX_STORED_TRACES = 1_000;
    private static final int BATCH = 100;

    private final TraceStore store;
    private final TraceAnalyzer analyzer;

    public TraceFinalizer(TraceStore store, TraceAnalyzer analyzer) {
        this.store = store;
        this.analyzer = analyzer;
    }

    @Scheduled(fixedDelay = 1_000)
    public void finalizeReadyTraces() {
        long now = System.currentTimeMillis();
        List<String> ready = store.findReadyToFinalize(now - ROOT_GRACE_MS, now - QUIET_TIMEOUT_MS, BATCH);
        for (String traceId : ready) {
            try {
                analyzer.finalizeTrace(traceId);
            } catch (RuntimeException | StackOverflowError e) {
                // One trace that cannot be analyzed must not block every trace queued behind it.
                log.warn("Could not analyze trace {}; showing it without analysis", traceId, e);
                store.markComplete(traceId);
            }
        }
        if (!ready.isEmpty()) {
            store.enforceRetention(MAX_STORED_TRACES);
        }
    }
}
