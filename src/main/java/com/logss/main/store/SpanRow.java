package com.logss.main.store;

/** One row of the spans table. */
public record SpanRow(
        String traceId,
        String spanId,
        String parentSpanId,
        String name,
        String category,
        String scopeName,
        long startNs,
        double durationMs,
        String status,
        String errorType,
        String errorMessage,
        String stacktrace,
        String codeFunction,
        String threadName,
        String dbSystem,
        String dbOperation,
        String dbTable,
        String sql,
        String sqlShape,
        String httpMethod,
        String httpUrl,
        String httpHost,
        Integer httpStatus,
        String msgSystem,
        String msgDestination,
        String rawAttrs) {

    public long endNs() {
        return startNs + (long) (durationMs * 1_000_000);
    }

    public boolean isDb() {
        return "db".equals(category);
    }

    public boolean isError() {
        return "error".equals(status);
    }

    public SpanRow withSqlShape(String shape) {
        return new SpanRow(traceId, spanId, parentSpanId, name, category, scopeName, startNs, durationMs, status,
                errorType, errorMessage, stacktrace, codeFunction, threadName, dbSystem, dbOperation, dbTable, sql,
                shape, httpMethod, httpUrl, httpHost, httpStatus, msgSystem, msgDestination, rawAttrs);
    }
}
