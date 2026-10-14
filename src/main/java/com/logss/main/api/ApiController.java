package com.logss.main.api;

import com.logss.main.store.TraceStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** JSON API for the web UI. */
@RestController
@RequestMapping("/api")
public class ApiController {
    private static final int MAX_LIMIT = 1_000;

    private final TraceStore store;

    public ApiController(TraceStore store) {
        this.store = store;
    }

    @GetMapping("/traces")
    public List<Map<String, Object>> traces(@RequestParam(required = false) String q,
                                            @RequestParam(name = "issues_only", defaultValue = "false") boolean issuesOnly,
                                            @RequestParam(name = "hide_warmup", defaultValue = "false") boolean hideWarmup,
                                            @RequestParam(name = "hide_noise", defaultValue = "false") boolean hideNoise,
                                            @RequestParam(defaultValue = "200") int limit) {
        return store.listTraces(q, issuesOnly, hideWarmup, hideNoise, Math.clamp(limit, 1, MAX_LIMIT));
    }

    @GetMapping("/traces/{traceId}")
    public ResponseEntity<Map<String, Object>> trace(@PathVariable String traceId) {
        return store.traceSummary(traceId)
                .map(summary -> ResponseEntity.ok(Map.<String, Object>of(
                        "trace", summary,
                        "spans", store.traceSpans(traceId),
                        "issues", store.traceIssues(traceId))))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/queries")
    public List<Map<String, Object>> queries(@RequestParam(defaultValue = "50") int limit) {
        return store.topQueries(Math.clamp(limit, 1, MAX_LIMIT));
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return store.stats();
    }

    @DeleteMapping("/traces")
    public ResponseEntity<Void> clear() {
        store.clear();
        return ResponseEntity.noContent().build();
    }
}
