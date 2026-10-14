package com.logss.main.analysis;

import com.logss.main.store.Issue;
import com.logss.main.store.SpanRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IssueDetectorTest {
    private final IssueDetector detector = new IssueDetector();

    @Test
    void cheapNPlusOneIsMediumAndExpensiveFailuresComeFirst() {
        List<SpanRow> spans = new ArrayList<>();
        spans.add(code("root", null, "Worker.run"));
        spans.add(code("repo", "root", "OrderRepository.findById"));
        for (int i = 0; i < 12; i++) {
            spans.add(db("q" + i, "repo", "select o.id from orders o where o.id=?", "shape-orders", 1.0));
        }
        for (int i = 0; i < 3; i++) {
            spans.add(httpError("h" + i, "root", 900));
        }

        List<Issue> issues = detector.detect(spans, true, 3000);

        Issue first = issues.getFirst();
        assertEquals("error", first.type());
        assertEquals("high", first.severity(), "2.7 s of a 3 s job is worth fixing first");
        assertEquals("3× POST api.example.com failed (HTTP 400)", first.title());

        Issue nPlusOne = issues.stream().filter(i -> i.type().equals("n_plus_one")).findFirst().orElseThrow();
        assertEquals("medium", nPlusOne.severity(), "12 ms in total is not high, however often it repeats");
        assertEquals("N+1 query on orders (12×)", nPlusOne.title());
        assertEquals("Worker.run → OrderRepository.findById", nPlusOne.codeFunction());
    }

    @Test
    void selectBeforeSaveIsExplainedAsMerge() {
        List<SpanRow> spans = new ArrayList<>();
        spans.add(code("root", null, "Worker.run"));
        spans.add(code("repo", "root", "InvocationRepository.saveAndFlush"));
        for (int i = 0; i < 6; i++) {
            spans.add(db("q" + i, "repo", "select i.id from invocation i where i.id=?", "shape-inv", 1.0));
        }

        Issue issue = detector.detect(spans, true, 100).getFirst();

        assertEquals("Extra SELECT before each save on invocation (6×)", issue.title());
        assertTrue(issue.suggestion().contains("Persistable#isNew()"));
    }

    @Test
    void lazyLoadingUnderAControllerGetsJpaAdvice() {
        // A lazy collection loads straight under the controller span; the repository call is a sibling.
        List<SpanRow> spans = new ArrayList<>();
        spans.add(code("root", null, "ShopController.listOrders"));
        spans.add(code("repo", "root", "OrderRepository.findAll"));
        for (int i = 0; i < 6; i++) {
            spans.add(db("q" + i, "root", "select i.id from order_item i where i.order_id=?", "shape-items", 1.0));
        }

        Issue issue = detector.detect(spans, true, 100).getFirst();

        assertEquals("N+1 query on order_item (6×)", issue.title());
        assertTrue(issue.suggestion().contains("JOIN FETCH"), issue.suggestion());
    }

    private static SpanRow code(String id, String parent, String function) {
        return span(id, parent, "code", function, null, null, null, null, 0, "unset", null);
    }

    private static SpanRow db(String id, String parent, String sql, String shape, double ms) {
        return span(id, parent, "db", null, sql, shape, "SELECT", null, ms, "unset", null);
    }

    private static SpanRow httpError(String id, String parent, double ms) {
        return span(id, parent, "http_client", null, null, null, null, "api.example.com", ms, "error", 400);
    }

    private static SpanRow span(String id, String parent, String category, String function, String sql, String shape,
                                String operation, String host, double ms, String status, Integer httpStatus) {
        String name = host == null ? id : "POST"; // the Java agent names client spans by HTTP method
        return new SpanRow("t1", id, parent, name, category, "io.opentelemetry.jdbc", 0, ms, status, null, null, null,
                function, null, sql == null ? null : "postgresql", operation, null, sql, shape,
                host == null ? null : "POST", null, host, httpStatus, null, null, "{}");
    }
}
