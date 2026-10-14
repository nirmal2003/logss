package com.logss.main.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** All SQL against the SQLite store lives here. */
@Repository
public class TraceStore {

    private static final String SEVERITY_ORDER = "CASE severity WHEN 'high' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END";

    private static final RowMapper<SpanRow> SPAN_ROW = (rs, i) -> new SpanRow(
            rs.getString("trace_id"),
            rs.getString("span_id"),
            rs.getString("parent_span_id"),
            rs.getString("name"),
            rs.getString("category"),
            rs.getString("scope_name"),
            rs.getLong("start_ns"),
            rs.getDouble("duration_ms"),
            rs.getString("status"),
            rs.getString("error_type"),
            rs.getString("error_message"),
            rs.getString("stacktrace"),
            rs.getString("code_function"),
            rs.getString("thread_name"),
            rs.getString("db_system"),
            rs.getString("db_operation"),
            rs.getString("db_table"),
            rs.getString("sql"),
            rs.getString("sql_shape"),
            rs.getString("http_method"),
            rs.getString("http_url"),
            rs.getString("http_host"),
            rs.getObject("http_status") instanceof Number n ? n.intValue() : null,
            rs.getString("msg_system"),
            rs.getString("msg_destination"),
            rs.getString("raw_attrs"));

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public TraceStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ---- Ingest -------------------------------------------------------------------------------

    /** Creates or reopens each trace. A trace that gets new spans after completing is analyzed again. */
    public void upsertTraceStubs(Collection<TraceStub> stubs, long nowMs) {
        jdbc.batchUpdate("""
                INSERT INTO traces (trace_id, service, instance_id, start_ns, has_root, last_received_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (trace_id) DO UPDATE SET
                    start_ns = MIN(start_ns, excluded.start_ns),
                    has_root = MAX(has_root, excluded.has_root),
                    last_received_at = excluded.last_received_at,
                    state = 'open'
                """, stubs.stream().map(s -> new Object[]{
                s.traceId(), s.service(), s.instanceId(), s.startNs(), s.hasRoot() ? 1 : 0, nowMs}).toList());
    }

    /** OTLP clients retry failed exports, so a span can arrive twice; the duplicate is ignored. */
    public void insertSpans(List<SpanRow> rows) {
        jdbc.batchUpdate("""
                INSERT OR IGNORE INTO spans (trace_id, span_id, parent_span_id, name, category, scope_name, start_ns,
                    duration_ms, status, error_type, error_message, stacktrace, code_function, thread_name, db_system,
                    db_operation, db_table, sql, sql_shape, http_method, http_url, http_host, http_status, msg_system,
                    msg_destination, raw_attrs)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows.stream().map(r -> new Object[]{
                r.traceId(), r.spanId(), r.parentSpanId(), r.name(), r.category(), r.scopeName(), r.startNs(),
                r.durationMs(), r.status(), r.errorType(), r.errorMessage(), r.stacktrace(), r.codeFunction(),
                r.threadName(), r.dbSystem(), r.dbOperation(), r.dbTable(), r.sql(), r.sqlShape(), r.httpMethod(),
                r.httpUrl(), r.httpHost(), r.httpStatus(), r.msgSystem(), r.msgDestination(), r.rawAttrs()}).toList());
    }

    public void setRoot(RootInfo root) {
        jdbc.update("""
                UPDATE traces SET kind = ?, name = ?, method = ?, route = ?, path = ?, query = ?, status_code = ?,
                    start_ns = ?, duration_ms = ?, has_root = 1
                WHERE trace_id = ?
                """, root.kind(), root.name(), root.method(), root.route(), root.path(), root.query(),
                root.statusCode(), root.startNs(), root.durationMs(), root.traceId());
    }

    // ---- Finalizing ---------------------------------------------------------------------------

    /** Open traces whose root arrived before {@code rootCutoff}, or that went quiet before {@code timeoutCutoff}. */
    public List<String> findReadyToFinalize(long rootCutoff, long timeoutCutoff, int limit) {
        return jdbc.queryForList("""
                SELECT trace_id FROM traces
                WHERE state = 'open'
                  AND ((has_root = 1 AND last_received_at <= ?) OR last_received_at <= ?)
                ORDER BY last_received_at
                LIMIT ?
                """, String.class, rootCutoff, timeoutCutoff, limit);
    }

    public Optional<Map<String, Object>> loadTrace(String traceId) {
        return jdbc.queryForList("SELECT * FROM traces WHERE trace_id = ?", traceId).stream().findFirst();
    }

    public List<SpanRow> loadSpans(String traceId) {
        return jdbc.query("SELECT * FROM spans WHERE trace_id = ? ORDER BY start_ns", SPAN_ROW, traceId);
    }

    public void updateSpanShape(String traceId, String spanId, String shapeHash) {
        jdbc.update("UPDATE spans SET sql_shape = ? WHERE trace_id = ? AND span_id = ?", shapeHash, traceId, spanId);
    }

    /** Adds one execution of a query shape to its running totals. */
    public void recordQueryShape(String shapeHash, String normalizedSql, SpanRow span, boolean firstInTrace, String routeKey) {
        jdbc.update("""
                INSERT INTO query_shapes (shape_hash, normalized_sql, db_system, db_table, db_operation, total_count,
                    total_time_ms, max_ms, trace_count, routes, first_seen_ns, last_seen_ns)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?, 1, json_array(?), ?, ?)
                ON CONFLICT (shape_hash) DO UPDATE SET
                    total_count = total_count + 1,
                    total_time_ms = total_time_ms + excluded.total_time_ms,
                    max_ms = MAX(max_ms, excluded.max_ms),
                    trace_count = trace_count + ?,
                    routes = CASE WHEN EXISTS (SELECT 1 FROM json_each(query_shapes.routes) WHERE value = ?)
                                  THEN routes ELSE json_insert(routes, '$[#]', ?) END,
                    first_seen_ns = MIN(first_seen_ns, excluded.first_seen_ns),
                    last_seen_ns = MAX(last_seen_ns, excluded.last_seen_ns)
                """, shapeHash, normalizedSql, span.dbSystem(), span.dbTable(), span.dbOperation(), span.durationMs(),
                span.durationMs(), routeKey, span.startNs(), span.startNs(), firstInTrace ? 1 : 0, routeKey, routeKey);
    }

    public void replaceIssues(String traceId, List<Issue> issues, long nowMs) {
        jdbc.update("DELETE FROM issues WHERE trace_id = ?", traceId);
        jdbc.batchUpdate("""
                INSERT INTO issues (trace_id, type, severity, title, shape_hash, span_ids, count, time_ms, share_pct,
                    code_function, suggestion, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, issues.stream().map(i -> new Object[]{
                traceId, i.type(), i.severity(), i.title(), i.shapeHash(), json.writeValueAsString(i.spanIds()),
                i.count(), i.timeMs(), i.sharePct(), i.codeFunction(), i.suggestion(), nowMs}).toList());
    }

    /** How many earlier calls of the same route this app instance has completed; the first few are warm-up. */
    public int countEarlierCalls(String service, String instanceId, String routeKey, long startNs) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM traces
                WHERE state = 'complete' AND service = ? AND instance_id IS ?
                  AND coalesce(route, name) = ? AND start_ns < ?
                """, Integer.class, service, instanceId, routeKey, startNs);
        return count == null ? 0 : count;
    }

    /**
     * Marks the trace complete with its summary. Returns false if more spans arrived while it was being
     * analyzed; the trace then stays open and is analyzed again.
     */
    public boolean completeTrace(String traceId, long expectedLastReceivedAt, TraceSummary s) {
        return jdbc.update("""
                UPDATE traces SET name = ?, start_ns = ?, duration_ms = ?, span_count = ?, db_count = ?, db_time_ms = ?,
                    http_out_count = ?, error_count = ?, issue_count = ?, top_issue = ?, is_warmup = ?, state = 'complete'
                WHERE trace_id = ? AND last_received_at = ?
                """, s.name(), s.startNs(), s.durationMs(), s.spanCount(), s.dbCount(), s.dbTimeMs(), s.httpOutCount(),
                s.errorCount(), s.issueCount(), s.topIssue(), s.warmup() ? 1 : 0, traceId, expectedLastReceivedAt) == 1;
    }

    /** Fallback when analysis fails, so the trace still shows up instead of being retried forever. */
    public void markComplete(String traceId) {
        jdbc.update("UPDATE traces SET state = 'complete' WHERE trace_id = ?", traceId);
    }

    /** Keeps the newest {@code max} completed traces; their spans and issues go with them. */
    public void enforceRetention(int max) {
        jdbc.update("""
                DELETE FROM traces WHERE trace_id IN (
                    SELECT trace_id FROM traces WHERE state = 'complete' ORDER BY start_ns DESC LIMIT -1 OFFSET ?)
                """, max);
    }

    // ---- UI API -------------------------------------------------------------------------------

    /**
     * @param hideNoise leaves out CORS preflights and standalone SQL (traces made only of database spans,
     *                  such as driver and Hibernate startup queries)
     */
    public List<Map<String, Object>> listTraces(String filter, boolean issuesOnly, boolean hideWarmup, boolean hideNoise,
                                                int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT t.trace_id, t.service, t.kind, t.name, t.method, t.route, t.path, t.status_code,
                       t.start_ns / 1000000 AS start_ms, t.duration_ms, t.span_count, t.db_count, t.db_time_ms,
                       t.http_out_count, t.error_count, t.issue_count, t.top_issue, t.has_root, t.is_warmup,
                       (SELECT severity FROM issues i WHERE i.trace_id = t.trace_id
                        ORDER BY %s LIMIT 1) AS top_severity
                FROM traces t
                WHERE t.state = 'complete'
                """.formatted(SEVERITY_ORDER));
        List<Object> args = new ArrayList<>();
        if (filter != null && !filter.isBlank()) {
            sql.append(" AND (t.name LIKE ? OR coalesce(t.route, '') LIKE ? OR coalesce(t.path, '') LIKE ?)");
            String like = "%" + filter.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (issuesOnly) {
            sql.append(" AND t.issue_count > 0");
        }
        if (hideWarmup) {
            sql.append(" AND t.is_warmup = 0");
        }
        if (hideNoise) {
            sql.append(" AND coalesce(t.method, '') <> 'OPTIONS' AND NOT (t.kind = 'job' AND t.span_count = t.db_count)");
        }
        sql.append(" ORDER BY t.start_ns DESC LIMIT ?");
        args.add(limit);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Optional<Map<String, Object>> traceSummary(String traceId) {
        return jdbc.queryForList("""
                SELECT trace_id, service, instance_id, kind, name, method, route, path, query, status_code,
                       start_ns / 1000000 AS start_ms, duration_ms, span_count, db_count, db_time_ms, http_out_count,
                       error_count, issue_count, top_issue, has_root, is_warmup, state
                FROM traces WHERE trace_id = ?
                """, traceId).stream().findFirst();
    }

    /** Spans with their offset from the trace start, so the browser never handles nanosecond timestamps. */
    public List<Map<String, Object>> traceSpans(String traceId) {
        return jdbc.queryForList("""
                SELECT s.span_id, s.parent_span_id, s.name, s.category, s.scope_name,
                       (s.start_ns - t.start_ns) / 1000000.0 AS offset_ms, s.duration_ms, s.status, s.error_type,
                       s.error_message, s.stacktrace, s.code_function, s.thread_name, s.db_system, s.db_operation,
                       s.db_table, s.sql, s.sql_shape, s.http_method, s.http_url, s.http_host, s.http_status,
                       s.msg_system, s.msg_destination, s.raw_attrs
                FROM spans s JOIN traces t ON t.trace_id = s.trace_id
                WHERE s.trace_id = ?
                ORDER BY s.start_ns
                """, traceId);
    }

    public List<Map<String, Object>> traceIssues(String traceId) {
        return jdbc.queryForList("SELECT * FROM issues WHERE trace_id = ? ORDER BY " + SEVERITY_ORDER + ", time_ms DESC",
                traceId);
    }

    /** Query shapes over the stored traces, most total time first. */
    public List<Map<String, Object>> topQueries(int limit) {
        return jdbc.queryForList("""
                SELECT q.shape_hash, q.normalized_sql, q.db_table, q.db_operation,
                       count(*) AS runs, count(DISTINCT s.trace_id) AS traces,
                       sum(s.duration_ms) AS total_ms, avg(s.duration_ms) AS avg_ms, max(s.duration_ms) AS max_ms,
                       group_concat(DISTINCT coalesce(t.route, t.name)) AS routes
                FROM spans s
                JOIN query_shapes q ON q.shape_hash = s.sql_shape
                JOIN traces t ON t.trace_id = s.trace_id
                WHERE t.state = 'complete'
                GROUP BY q.shape_hash
                ORDER BY total_ms DESC
                LIMIT ?
                """, limit);
    }

    public Map<String, Object> stats() {
        return jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM traces WHERE state = 'complete') AS traces,
                       (SELECT count(*) FROM traces WHERE state = 'open') AS open,
                       (SELECT count(*) FROM issues) AS issues
                """);
    }

    public void clear() {
        jdbc.update("DELETE FROM issues");
        jdbc.update("DELETE FROM spans");
        jdbc.update("DELETE FROM traces");
        jdbc.update("DELETE FROM query_shapes");
    }
}
