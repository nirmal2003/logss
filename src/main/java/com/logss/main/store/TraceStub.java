package com.logss.main.store;

/** What one batch tells us about a trace before it is complete. */
public record TraceStub(String traceId, String service, String instanceId, long startNs, boolean hasRoot) {

    public TraceStub combine(TraceStub other) {
        return new TraceStub(traceId, service, instanceId, Math.min(startNs, other.startNs), hasRoot || other.hasRoot);
    }
}
