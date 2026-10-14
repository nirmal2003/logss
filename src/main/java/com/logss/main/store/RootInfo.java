package com.logss.main.store;

/** What a trace's root span tells us about the request or job as a whole. */
public record RootInfo(
        String traceId,
        String kind,
        String name,
        String method,
        String route,
        String path,
        String query,
        Integer statusCode,
        long startNs,
        double durationMs) {
}
