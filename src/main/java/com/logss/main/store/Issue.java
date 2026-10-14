package com.logss.main.store;

import java.util.List;

/** A detected problem in one trace. Severity is one of high, medium, info. */
public record Issue(
        String type,
        String severity,
        String title,
        String shapeHash,
        List<String> spanIds,
        Integer count,
        Double timeMs,
        Double sharePct,
        String codeFunction,
        String suggestion) {

    public int severityRank() {
        return switch (severity) {
            case "high" -> 0;
            case "medium" -> 1;
            default -> 2;
        };
    }
}
