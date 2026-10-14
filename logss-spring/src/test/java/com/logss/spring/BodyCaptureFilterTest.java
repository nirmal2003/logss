package com.logss.spring;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BodyCaptureFilterTest {
    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    private final BodyCaptureFilter filter = new BodyCaptureFilter(64, new BodyMasker(new LogssCaptureProperties().getMaskedFields()));

    /** A controller stand-in: reads the JSON body and answers with JSON. */
    private final FilterChain loginController = (request, response) -> {
        request.getInputStream().readAllBytes();
        response.setContentType("application/json");
        response.getWriter().write("{\"token\":\"abc\",\"user\":1}");
        response.getWriter().flush();
    };

    @Test
    void recordsMaskedBodiesAndStillSendsTheRealResponse() throws Exception {
        MockHttpServletRequest request = jsonPost("{\"email\":\"a@b.c\",\"password\":\"hunter2\"}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        SpanData span = runInServerSpan(request, response, loginController);

        assertEquals("{\"email\":\"a@b.c\",\"password\":\"***\"}", span.getAttributes().get(BodyCaptureFilter.REQUEST_BODY));
        assertEquals("{\"token\":\"***\",\"user\":1}", span.getAttributes().get(BodyCaptureFilter.RESPONSE_BODY));
        assertEquals("{\"token\":\"abc\",\"user\":1}", response.getContentAsString(), "the client gets the unmasked body");
    }

    @Test
    void cutsOffLongBodies() throws Exception {
        String longJson = "{\"note\":\"" + "x".repeat(100) + "\"}";
        SpanData span = runInServerSpan(jsonPost(longJson), new MockHttpServletResponse(), loginController);

        String recorded = span.getAttributes().get(BodyCaptureFilter.REQUEST_BODY);
        assertEquals(longJson.substring(0, 64) + "… [truncated, " + longJson.length() + " bytes]", recorded);
    }

    @Test
    void describesBinaryBodiesInsteadOfStoringThem() throws Exception {
        FilterChain download = (request, response) -> {
            response.setContentType("image/png");
            response.getOutputStream().write(new byte[]{1, 2, 3});
        };
        SpanData span = runInServerSpan(new MockHttpServletRequest("GET", "/logo.png"), new MockHttpServletResponse(), download);

        assertNull(span.getAttributes().get(BodyCaptureFilter.REQUEST_BODY));
        assertEquals("[image/png, 3 bytes]", span.getAttributes().get(BodyCaptureFilter.RESPONSE_BODY));
    }

    @Test
    void passesTheResponseThroughWhenNoAgentIsRunning() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(jsonPost("{}"), response, loginController);
        assertEquals("{\"token\":\"abc\",\"user\":1}", response.getContentAsString());
    }

    private SpanData runInServerSpan(MockHttpServletRequest request, MockHttpServletResponse response, FilterChain chain)
            throws Exception {
        Span span = tracerProvider.get("test").spanBuilder(request.getMethod() + " " + request.getRequestURI()).startSpan();
        try (Scope ignored = span.makeCurrent()) {
            filter.doFilter(request, response, chain);
        } finally {
            span.end();
        }
        var finished = exporter.getFinishedSpanItems();
        return finished.get(finished.size() - 1);
    }

    private static MockHttpServletRequest jsonPost(String json) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setContentType("application/json");
        request.setCharacterEncoding("UTF-8");
        request.setContent(json.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
