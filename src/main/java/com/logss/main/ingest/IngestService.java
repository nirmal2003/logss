package com.logss.main.ingest;

import com.logss.main.store.RootInfo;
import com.logss.main.store.SpanRow;
import com.logss.main.store.TraceStore;
import com.logss.main.store.TraceStub;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stores one OTLP batch. Traces stay 'open' here; {@link com.logss.main.analysis.TraceFinalizer}
 * completes them once their spans have stopped arriving.
 */
@Service
public class IngestService {

    private final SpanMapper mapper;
    private final TraceStore store;

    public IngestService(SpanMapper mapper, TraceStore store) {
        this.mapper = mapper;
        this.store = store;
    }

    @Transactional
    public int ingest(ExportTraceServiceRequest request) {
        List<SpanRow> rows = new ArrayList<>();
        List<RootInfo> roots = new ArrayList<>();
        Map<String, TraceStub> stubs = new LinkedHashMap<>();

        for (ResourceSpans resourceSpans : request.getResourceSpansList()) {
            Map<String, Object> resource = Attributes.toMap(resourceSpans.getResource().getAttributesList());
            String service = Attributes.str(resource, "service.name");
            String instanceId = Attributes.str(resource, "service.instance.id");

            for (ScopeSpans scopeSpans : resourceSpans.getScopeSpansList()) {
                String scopeName = scopeSpans.getScope().getName();

                for (Span span : scopeSpans.getSpansList()) {
                    SpanMapper.Mapped mapped = mapper.map(span, scopeName);
                    SpanRow row = mapped.row();
                    rows.add(row);

                    if (mapped.root() != null) {
                        roots.add(mapped.root());
                    }

                    stubs.merge(row.traceId(),
                            new TraceStub(row.traceId(), service == null ? "unknown" : service, instanceId,
                                    row.startNs(), mapped.root() != null),
                            TraceStub::combine);
                }
            }
        }

        store.upsertTraceStubs(stubs.values(), System.currentTimeMillis());
        store.insertSpans(rows);
        roots.forEach(store::setRoot);
        return rows.size();
    }
}
