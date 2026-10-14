package com.logss.main.otlp;

import com.google.protobuf.InvalidProtocolBufferException;
import com.logss.main.ingest.IngestService;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;

/**
 * OTLP/HTTP trace receiver. Apps send here with OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
 * and OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf.
 */
@RestController
public class OtlpController {
    private static final Logger log = LoggerFactory.getLogger(OtlpController.class);

    private final IngestService ingestService;

    public OtlpController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping(path = "/v1/traces", consumes = "application/x-protobuf", produces = "application/x-protobuf")
    public ResponseEntity<byte[]> exportTraces(@RequestBody byte[] body, @RequestHeader(value = "Content-Encoding", required = false) String encoding) {
        ExportTraceServiceRequest request;
        try {
            byte[] payload = "gzip".equalsIgnoreCase(encoding) ? gunzip(body) : body;
            request = ExportTraceServiceRequest.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            log.warn("Rejected malformed OTLP payload: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (IOException e) {
            log.warn("Rejected OTLP payload that is not valid gzip: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }

        int spans = ingestService.ingest(request);
        log.debug("Stored {} spans", spans);
        return ResponseEntity.ok(ExportTraceServiceResponse.getDefaultInstance().toByteArray());
    }

    private static byte[] gunzip(byte[] body) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
            return in.readAllBytes();
        }
    }
}
