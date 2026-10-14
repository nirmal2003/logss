package com.logss.main.ingest;

import com.logss.main.store.RootInfo;
import com.logss.main.store.SpanRow;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Span.SpanKind;
import io.opentelemetry.proto.trace.v1.Status;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.HexFormat;
import java.util.Map;

import static com.logss.main.ingest.Attributes.integer;
import static com.logss.main.ingest.Attributes.str;

/**
 * Pulls the fields worth keeping out of an OTLP span. Reads both the current and the older
 * semantic-convention names, since agent versions differ (db.query.text vs db.statement, etc.).
 */
@Component
public class SpanMapper {
    private static final HexFormat HEX = HexFormat.of();
    private static final int STACKTRACE_LINES = 30;

    private final ObjectMapper json;

    public SpanMapper(ObjectMapper json) {
        this.json = json;
    }

    /** {@code root} is set when this span starts the local unit of work (no parent, or an incoming request/message). */
    public record Mapped(SpanRow row, RootInfo root) {
    }

    public Mapped map(Span span, String scopeName) {
        Map<String, Object> attrs = Attributes.toMap(span.getAttributesList());
        String traceId = HEX.formatHex(span.getTraceId().toByteArray());
        String parentSpanId = span.getParentSpanId().isEmpty() ? null : HEX.formatHex(span.getParentSpanId().toByteArray());
        SpanKind kind = span.getKind();
        long startNs = span.getStartTimeUnixNano();
        double durationMs = (span.getEndTimeUnixNano() - startNs) / 1_000_000.0;

        String codeFunction = codeFunction(attrs);
        String dbSystem = str(attrs, "db.system.name", "db.system");
        String httpMethod = str(attrs, "http.request.method", "http.method");
        String category = category(kind, scopeName, attrs, dbSystem, httpMethod, codeFunction);

        String status = switch (span.getStatus().getCode()) {
            case STATUS_CODE_ERROR -> "error";
            case STATUS_CODE_OK -> "ok";
            default -> "unset";
        };
        String errorType = null;
        String errorMessage = blankToNull(span.getStatus().getMessage());
        String stacktrace = null;
        for (Span.Event event : span.getEventsList()) {
            if ("exception".equals(event.getName())) {
                Map<String, Object> ev = Attributes.toMap(event.getAttributesList());
                errorType = str(ev, "exception.type");
                String message = str(ev, "exception.message");
                if (message != null) {
                    errorMessage = message;
                }
                stacktrace = firstLines(str(ev, "exception.stacktrace"));
                break;
            }
        }
        if (errorType == null && span.getStatus().getCode() == Status.StatusCode.STATUS_CODE_ERROR) {
            errorType = str(attrs, "error.type");
        }

        boolean isHttp = "http_server".equals(category) || "http_client".equals(category);
        String path = str(attrs, "url.path", "http.target");
        String query = str(attrs, "url.query");
        Integer httpStatus = isHttp ? integer(attrs, "http.response.status_code", "http.status_code") : null;
        String httpUrl = null;
        if ("http_client".equals(category)) {
            httpUrl = str(attrs, "url.full", "http.url");
        } else if ("http_server".equals(category) && path != null) {
            httpUrl = query == null ? path : path + "?" + query;
        }

        boolean isDb = "db".equals(category);
        SpanRow row = new SpanRow(
                traceId,
                HEX.formatHex(span.getSpanId().toByteArray()),
                parentSpanId,
                span.getName(),
                category,
                scopeName,
                startNs,
                durationMs,
                status,
                errorType,
                errorMessage,
                stacktrace,
                codeFunction,
                str(attrs, "thread.name"),
                dbSystem,
                isDb ? str(attrs, "db.operation.name", "db.operation") : null,
                isDb ? str(attrs, "db.collection.name", "db.sql.table") : null,
                isDb ? str(attrs, "db.query.text", "db.statement") : null,
                null,
                isHttp ? httpMethod : null,
                httpUrl,
                "http_client".equals(category) ? str(attrs, "server.address", "net.peer.name") : null,
                httpStatus,
                str(attrs, "messaging.system"),
                str(attrs, "messaging.destination.name"),
                json.writeValueAsString(attrs));

        boolean isRoot = parentSpanId == null || kind == SpanKind.SPAN_KIND_SERVER || kind == SpanKind.SPAN_KIND_CONSUMER;
        RootInfo root = null;
        if (isRoot) {
            String traceKind = switch (category) {
                case "http_server" -> "http";
                case "messaging" -> "message";
                default -> "job";
            };
            root = new RootInfo(traceId, traceKind, span.getName(), httpMethod, str(attrs, "http.route"), path, query,
                    httpStatus, startNs, durationMs);
        }
        return new Mapped(row, root);
    }

    private static String category(SpanKind kind, String scopeName, Map<String, Object> attrs, String dbSystem,
                                   String httpMethod, String codeFunction) {
        if (kind == SpanKind.SPAN_KIND_SERVER) {
            return "http_server";
        }
        if (dbSystem != null && kind == SpanKind.SPAN_KIND_CLIENT) {
            return "db";
        }
        if (kind == SpanKind.SPAN_KIND_CLIENT && httpMethod != null) {
            return "http_client";
        }
        if (kind == SpanKind.SPAN_KIND_PRODUCER || kind == SpanKind.SPAN_KIND_CONSUMER || attrs.containsKey("messaging.system")) {
            return "messaging";
        }
        if (codeFunction != null || scopeName.contains("hibernate")) {
            return "code";
        }
        return "other";
    }

    /** "UserRepository.findByEmail" from either code.function.name or code.namespace + code.function. */
    private static String codeFunction(Map<String, Object> attrs) {
        String full = str(attrs, "code.function.name");
        if (full != null) {
            int last = full.lastIndexOf('.');
            int previous = last > 0 ? full.lastIndexOf('.', last - 1) : -1;
            return full.substring(previous + 1);
        }
        String function = str(attrs, "code.function");
        if (function == null) {
            return null;
        }
        String namespace = str(attrs, "code.namespace");
        return namespace == null ? function : namespace.substring(namespace.lastIndexOf('.') + 1) + "." + function;
    }

    private static String firstLines(String text) {
        if (text == null) {
            return null;
        }
        return text.lines().limit(STACKTRACE_LINES).reduce((a, b) -> a + "\n" + b).orElse(text);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
