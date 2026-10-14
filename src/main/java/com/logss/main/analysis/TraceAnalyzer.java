package com.logss.main.analysis;

import com.logss.main.store.Issue;
import com.logss.main.store.SpanRow;
import com.logss.main.store.TraceStore;
import com.logss.main.store.TraceSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Completes one trace: query shapes, counts, issues and the warm-up flag. */
@Service
public class TraceAnalyzer {
    /** The first calls of a route after the app starts are slow (JIT, connection pool, caches). */
    static final int WARMUP_CALLS = 3;

    private final TraceStore store;
    private final IssueDetector detector;

    public TraceAnalyzer(TraceStore store, IssueDetector detector) {
        this.store = store;
        this.detector = detector;
    }

    @Transactional
    public void finalizeTrace(String traceId) {
        Map<String, Object> trace = store.loadTrace(traceId).orElse(null);
        if (trace == null) {
            return;
        }
        List<SpanRow> spans = new ArrayList<>(store.loadSpans(traceId));
        if (spans.isEmpty()) {
            return;
        }
        long lastReceivedAt = ((Number) trace.get("last_received_at")).longValue();
        boolean hasRoot = ((Number) trace.get("has_root")).intValue() == 1;

        // Without a root span, the trace covers everything from the first span's start to the last span's end.
        String name = (String) trace.get("name");
        long startNs;
        double durationMs;
        if (hasRoot && name != null && trace.get("duration_ms") instanceof Number rootDuration) {
            startNs = ((Number) trace.get("start_ns")).longValue();
            durationMs = rootDuration.doubleValue();
        } else {
            startNs = spans.stream().mapToLong(SpanRow::startNs).min().orElseThrow();
            long endNs = spans.stream().mapToLong(SpanRow::endNs).max().orElseThrow();
            name = spans.getFirst().name();
            durationMs = (endNs - startNs) / 1_000_000.0;
        }
        // SQL that ran outside any request (driver or Hibernate startup queries) is named by its statement,
        // since the agent names those spans after the database, which says little.
        if (spans.stream().allMatch(SpanRow::isDb) && spans.getFirst().sql() != null) {
            name = abbreviate(spans.getFirst().sql());
        }
        String route = (String) trace.get("route");
        String routeKey = route != null ? route : name;

        recordQueryShapes(traceId, spans, routeKey);
        List<Issue> issues = detector.detect(spans, hasRoot, durationMs);
        store.replaceIssues(traceId, issues, System.currentTimeMillis());

        boolean warmup = store.countEarlierCalls((String) trace.get("service"), (String) trace.get("instance_id"),
                routeKey, startNs) < WARMUP_CALLS;
        List<SpanRow> db = spans.stream().filter(SpanRow::isDb).toList();
        store.completeTrace(traceId, lastReceivedAt, new TraceSummary(
                name,
                startNs,
                durationMs,
                spans.size(),
                db.size(),
                db.stream().mapToDouble(SpanRow::durationMs).sum(),
                (int) spans.stream().filter(s -> "http_client".equals(s.category())).count(),
                (int) spans.stream().filter(SpanRow::isError).count(),
                issues.size(),
                issues.isEmpty() ? null : issues.getFirst().title(),
                warmup));
    }

    private static String abbreviate(String sql) {
        String oneLine = sql.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 80 ? oneLine : oneLine.substring(0, 79) + "…";
    }

    /**
     * Shapes only the spans not shaped yet, so a trace analyzed again (late spans) is not counted twice.
     */
    private void recordQueryShapes(String traceId, List<SpanRow> spans, String routeKey) {
        Set<String> shapesInTrace = new HashSet<>();
        spans.stream().map(SpanRow::sqlShape).filter(s -> s != null).forEach(shapesInTrace::add);

        for (int i = 0; i < spans.size(); i++) {
            SpanRow span = spans.get(i);
            if (!span.isDb() || span.sql() == null || span.sqlShape() != null) {
                continue;
            }
            String normalized = SqlShapes.normalize(span.sql());
            String hash = SqlShapes.hash(normalized);
            store.updateSpanShape(traceId, span.spanId(), hash);
            store.recordQueryShape(hash, normalized, span, shapesInTrace.add(hash), routeKey);
            spans.set(i, span.withSqlShape(hash));
        }
    }
}
