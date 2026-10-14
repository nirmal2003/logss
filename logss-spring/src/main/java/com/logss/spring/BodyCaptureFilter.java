package com.logss.spring;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.WebUtils;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Adds the request and response bodies to the server span the OpenTelemetry Java agent created for this
 * request, as {@code http.request.body} and {@code http.response.body}.
 *
 * <p>The response is buffered until the request finishes, so streaming responses (server-sent events)
 * are skipped.
 */
public class BodyCaptureFilter extends OncePerRequestFilter {
    public static final AttributeKey<String> REQUEST_BODY = AttributeKey.stringKey("http.request.body");
    public static final AttributeKey<String> RESPONSE_BODY = AttributeKey.stringKey("http.response.body");

    private final int maxBytes;
    private final BodyMasker masker;

    public BodyCaptureFilter(int maxBytes, BodyMasker masker) {
        this.maxBytes = maxBytes;
        this.masker = masker;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/event-stream");
    }

    /** Async requests finish on a later dispatch; the body is recorded and released there. */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingRequestWrapper cachedRequest = WebUtils.getNativeRequest(request, ContentCachingRequestWrapper.class);
        HttpServletRequest requestToUse = request;
        if (cachedRequest == null) {
            cachedRequest = new ContentCachingRequestWrapper(request, maxBytes);
            requestToUse = cachedRequest;
        }
        ContentCachingResponseWrapper cachedResponse = WebUtils.getNativeResponse(response, ContentCachingResponseWrapper.class);
        HttpServletResponse responseToUse = response;
        if (cachedResponse == null) {
            cachedResponse = new ContentCachingResponseWrapper(response);
            responseToUse = cachedResponse;
        }

        Span span = Span.current();
        try {
            chain.doFilter(requestToUse, responseToUse);
        } finally {
            if (!isAsyncStarted(requestToUse)) {
                record(span, cachedRequest, cachedResponse);
                cachedResponse.copyBodyToResponse();
            }
        }
    }

    private void record(Span span, ContentCachingRequestWrapper request, ContentCachingResponseWrapper response) {
        if (!span.isRecording()) {
            return; // no agent, or this request is not sampled
        }
        // The request wrapper stops caching at maxBytes, so the full size comes from Content-Length.
        byte[] requestBytes = request.getContentAsByteArray();
        long requestSize = Math.max(request.getContentLengthLong(), requestBytes.length);
        String requestBody = describe(requestBytes, requestSize, request.getContentType(), request.getCharacterEncoding());
        if (requestBody != null) {
            span.setAttribute(REQUEST_BODY, requestBody);
        }
        byte[] responseBytes = response.getContentAsByteArray();
        String responseBody = describe(responseBytes, responseBytes.length, response.getContentType(), response.getCharacterEncoding());
        if (responseBody != null) {
            span.setAttribute(RESPONSE_BODY, responseBody);
        }
    }

    /** The body as masked text, a short note for binary content, or null when there is none. */
    String describe(byte[] body, long totalBytes, String contentType, String encoding) {
        if (body.length == 0) {
            return null;
        }
        if (!isText(contentType)) {
            return "[" + contentType + ", " + totalBytes + " bytes]";
        }
        Charset charset = encoding != null && Charset.isSupported(encoding) ? Charset.forName(encoding) : StandardCharsets.UTF_8;
        int length = Math.min(body.length, maxBytes);
        String text = masker.mask(new String(body, 0, length, charset));
        return totalBytes > maxBytes ? text + "… [truncated, " + totalBytes + " bytes]" : text;
    }

    private static boolean isText(String contentType) {
        if (contentType == null) {
            return true;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.startsWith("text/") || type.contains("json") || type.contains("xml")
                || type.contains("x-www-form-urlencoded") || type.contains("graphql") || type.contains("javascript");
    }
}
