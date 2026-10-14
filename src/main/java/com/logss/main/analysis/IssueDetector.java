package com.logss.main.analysis;

import com.logss.main.store.Issue;
import com.logss.main.store.SpanRow;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Turns the spans of one completed trace into a short list of problems. */
@Component
public class IssueDetector {
    static final int REPEAT_THRESHOLD = 5;
    /** A repeated query is only "high" when it actually costs time: this share of the trace, or this many ms. */
    static final double HIGH_SHARE_PCT = 30;
    static final double HIGH_TIME_MS = 100;
    static final double SLOW_QUERY_MS = 100;
    static final double VERY_SLOW_QUERY_MS = 500;
    static final double SLOW_HTTP_MS = 500;
    private static final int MAX_EVIDENCE_SPANS = 50;
    private static final int MAX_SLOW_ITEMS = 3;
    private static final Set<String> WRITE_OPERATIONS = Set.of("INSERT", "UPDATE", "DELETE", "MERGE");
    private static final Pattern TABLE_IN_SQL = Pattern.compile("(?i)\\b(?:from|into|update)\\s+([\\w.\"`]+)");

    public List<Issue> detect(List<SpanRow> spans, boolean hasRoot, double traceMs) {
        Map<String, SpanRow> byId = spans.stream().collect(Collectors.toMap(SpanRow::spanId, Function.identity(), (a, b) -> a));
        List<Issue> issues = new ArrayList<>();
        repeatedQueries(spans, byId, traceMs, issues);
        slowQueries(spans, byId, issues);
        errors(spans, byId, traceMs, issues);
        slowCalls(spans, byId, issues);
        if (!hasRoot) {
            issues.add(new Issue("missing_root", "info", "Root span never arrived", null, List.of(), null, null, null,
                    null, "The work started without a parent (for example @Async or a thread pool that lost the trace "
                    + "context), or it was still running when the trace was closed."));
        }
        issues.sort(Comparator.comparingInt(Issue::severityRank)
                .thenComparing(i -> i.timeMs() == null ? 0 : -i.timeMs()));
        return issues;
    }

    /** The same query shape many times in one trace: N+1 for reads, unbatched writes otherwise. */
    private void repeatedQueries(List<SpanRow> spans, Map<String, SpanRow> byId, double traceMs, List<Issue> issues) {
        Map<String, List<SpanRow>> byShape = spans.stream()
                .filter(s -> s.isDb() && s.sqlShape() != null)
                .collect(Collectors.groupingBy(SpanRow::sqlShape, LinkedHashMap::new, Collectors.toList()));

        for (Map.Entry<String, List<SpanRow>> group : byShape.entrySet()) {
            List<SpanRow> runs = group.getValue();
            if (runs.size() < REPEAT_THRESHOLD) {
                continue;
            }
            SpanRow first = runs.getFirst();
            double timeMs = runs.stream().mapToDouble(SpanRow::durationMs).sum();
            Double share = share(timeMs, traceMs);
            String severity = costly(timeMs, share) ? "high" : "medium";
            String table = table(first);
            boolean write = first.dbOperation() != null && WRITE_OPERATIONS.contains(first.dbOperation().toUpperCase());
            boolean orm = usesOrm(first, byId);
            String where = location(first, byId);
            List<String> ids = runs.stream().limit(MAX_EVIDENCE_SPANS).map(SpanRow::spanId).toList();

            if (write) {
                issues.add(new Issue("repeated_query", severity,
                        "Repeated %s on %s (%d×)".formatted(first.dbOperation().toUpperCase(), table, runs.size()),
                        group.getKey(), ids, runs.size(), timeMs, share, where,
                        orm ? "Each row is written in its own statement. Use saveAll(...) instead of save/saveAndFlush "
                                + "in a loop, and set spring.jpa.properties.hibernate.jdbc.batch_size so Hibernate "
                                + "sends them as one batch."
                            : "Each row is written in its own statement. Send them as one JDBC batch."));
            } else if (where != null && where.matches(".*Repository\\.save\\w*$")) {
                // Spring Data's save() calls merge() for entities whose ID is set by the app, and merge() loads the row first.
                issues.add(new Issue("repeated_query", severity,
                        "Extra SELECT before each save on %s (%d×)".formatted(table, runs.size()),
                        group.getKey(), ids, runs.size(), timeMs, share, where,
                        "save() on an entity whose ID your code sets runs merge(), which loads the row first. "
                                + "Use a generated ID, or implement Persistable#isNew() so new rows skip the SELECT."));
            } else {
                issues.add(new Issue("n_plus_one", severity,
                        "N+1 query on %s (%d×)".formatted(table, runs.size()),
                        group.getKey(), ids, runs.size(), timeMs, share, where,
                        orm ? "The same query runs once per item. Load them in one query: JOIN FETCH or @EntityGraph "
                                + "on the repository method, findAllById(...) for a list of IDs, or set "
                                + "spring.jpa.properties.hibernate.default_batch_fetch_size."
                            : "The same query runs once per item. Load all items in one query with IN (...) or a JOIN."));
            }
        }
    }

    private void slowQueries(List<SpanRow> spans, Map<String, SpanRow> byId, List<Issue> issues) {
        spans.stream()
                .filter(s -> s.isDb() && s.durationMs() >= SLOW_QUERY_MS)
                .sorted(Comparator.comparingDouble(SpanRow::durationMs).reversed())
                .limit(MAX_SLOW_ITEMS)
                .forEach(s -> issues.add(new Issue("slow_query", s.durationMs() >= VERY_SLOW_QUERY_MS ? "high" : "medium",
                        "Slow query on %s (%s)".formatted(table(s), formatMs(s.durationMs())),
                        s.sqlShape(), List.of(s.spanId()), 1, s.durationMs(), null, location(s, byId),
                        "Run EXPLAIN on this query and look for a sequential scan or a missing index on the columns "
                                + "used in WHERE, JOIN or ORDER BY.")));
    }

    /** One issue per kind of failure, so twelve identical failed calls read as one line. */
    private void errors(List<SpanRow> spans, Map<String, SpanRow> byId, double traceMs, List<Issue> issues) {
        Map<String, List<SpanRow>> groups = spans.stream()
                .filter(SpanRow::isError)
                .collect(Collectors.groupingBy(
                        s -> String.join("|", s.category(), s.name(), String.valueOf(s.errorType()), String.valueOf(s.errorMessage())),
                        LinkedHashMap::new, Collectors.toList()));

        for (List<SpanRow> group : groups.values()) {
            SpanRow first = group.getFirst();
            int n = group.size();
            String times = n > 1 ? n + "× " : "";
            boolean requestFailed = group.stream().anyMatch(s -> s.parentSpanId() == null || "http_server".equals(s.category()));
            double timeMs = group.stream().mapToDouble(SpanRow::durationMs).sum();
            Double share = share(timeMs, traceMs);
            List<String> ids = group.stream().limit(MAX_EVIDENCE_SPANS).map(SpanRow::spanId).toList();

            String title;
            String suggestion;
            if ("http_client".equals(first.category())) {
                String reason = first.httpStatus() != null ? "HTTP " + first.httpStatus()
                        : first.errorType() != null ? first.errorType() : "error";
                String target = first.httpHost() != null ? first.httpHost() : first.name();
                title = "%s%s %s failed (%s)".formatted(times, first.httpMethod() != null ? first.httpMethod() : "Call", target, reason);
                suggestion = first.httpStatus() != null && first.httpStatus() < 500
                        ? "The remote service rejected the request. Check the request data and the credentials sent with it."
                        : "The remote service failed or was unreachable. Consider a timeout and a retry with backoff.";
            } else if (first.errorType() != null && first.errorType().contains(".")) {
                String exception = first.errorType().substring(first.errorType().lastIndexOf('.') + 1);
                title = times + exception + (first.errorMessage() != null ? ": " + truncate(first.errorMessage(), 120) : "");
                suggestion = "See the stack trace on the highlighted span.";
            } else {
                title = times + first.name() + " failed" + (first.errorType() != null ? " (" + first.errorType() + ")" : "");
                suggestion = first.errorMessage() != null ? truncate(first.errorMessage(), 200) : null;
            }
            boolean high = requestFailed || (share != null && share >= HIGH_SHARE_PCT);
            issues.add(new Issue("error", high ? "high" : "medium", title, null, ids, n, timeMs, share,
                    location(first, byId), suggestion));
        }
    }

    private void slowCalls(List<SpanRow> spans, Map<String, SpanRow> byId, List<Issue> issues) {
        spans.stream()
                .filter(s -> "http_client".equals(s.category()) && !s.isError() && s.durationMs() >= SLOW_HTTP_MS)
                .sorted(Comparator.comparingDouble(SpanRow::durationMs).reversed())
                .limit(MAX_SLOW_ITEMS)
                .forEach(s -> issues.add(new Issue("slow_http", "medium",
                        "Slow call to %s (%s)".formatted(s.httpHost() != null ? s.httpHost() : s.name(), formatMs(s.durationMs())),
                        null, List.of(s.spanId()), 1, s.durationMs(), null, location(s, byId),
                        "Cache the result, call it asynchronously, or set a shorter timeout.")));
    }

    /**
     * Where in the code a span came from, e.g. "ShopController.importProducts → ProductRepository.save":
     * the nearest repository method and the first application method above it.
     */
    static String location(SpanRow span, Map<String, SpanRow> byId) {
        String repository = null;
        String caller = null;
        String nearest = null;
        SpanRow current = span;
        for (int depth = 0; current != null && depth < 100; depth++) {
            String fn = current.codeFunction();
            if (fn != null) {
                if (nearest == null) {
                    nearest = fn;
                }
                if (repository == null && fn.contains("Repository")) {
                    repository = fn;
                } else if (repository != null && !fn.contains("Repository")) {
                    caller = fn;
                    break;
                }
            }
            current = current.parentSpanId() == null ? null : byId.get(current.parentSpanId());
        }
        if (repository != null) {
            return caller != null ? caller + " → " + repository : repository;
        }
        return nearest;
    }

    /**
     * Whether the app talks to the database through JPA, so the advice can name JPA fixes. Looks at the whole
     * trace, not only the span's ancestors: a lazy collection load runs straight under the controller span,
     * with no Hibernate or repository span above it.
     */
    private static boolean usesOrm(SpanRow span, Map<String, SpanRow> byId) {
        return byId.values().stream().anyMatch(s -> {
            String scope = s.scopeName() == null ? "" : s.scopeName();
            return scope.contains("hibernate") || scope.contains("spring-data")
                    || (s.codeFunction() != null && s.codeFunction().contains("Repository"));
        });
    }

    private static Double share(double timeMs, double traceMs) {
        return traceMs > 0 ? Math.min(100, timeMs / traceMs * 100) : null;
    }

    private static boolean costly(double timeMs, Double sharePct) {
        return timeMs >= HIGH_TIME_MS || (sharePct != null && sharePct >= HIGH_SHARE_PCT);
    }

    /** The table name the agent reported, else the first table after FROM/INTO/UPDATE in the SQL. */
    static String table(SpanRow span) {
        if (span.dbTable() != null) {
            return span.dbTable();
        }
        if (span.sql() != null) {
            Matcher m = TABLE_IN_SQL.matcher(span.sql());
            if (m.find()) {
                return m.group(1);
            }
        }
        return "the database";
    }

    static String formatMs(double ms) {
        if (ms >= 1000) {
            return "%.2f s".formatted(ms / 1000);
        }
        return ms >= 10 ? "%.0f ms".formatted(ms) : "%.1f ms".formatted(ms);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
