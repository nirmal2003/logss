package com.logss.main.store;

/** Totals written to a trace when it is completed. */
public record TraceSummary(
        String name,
        long startNs,
        double durationMs,
        int spanCount,
        int dbCount,
        double dbTimeMs,
        int httpOutCount,
        int errorCount,
        int issueCount,
        String topIssue,
        boolean warmup) {
}
