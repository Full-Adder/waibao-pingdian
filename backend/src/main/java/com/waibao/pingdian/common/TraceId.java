package com.waibao.pingdian.common;

import org.slf4j.MDC;

public final class TraceId {

    private TraceId() {
    }

    public static String current() {
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        return traceId == null ? "" : traceId;
    }
}
