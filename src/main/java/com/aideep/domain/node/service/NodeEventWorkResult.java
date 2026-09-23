package com.aideep.domain.node.service;

import com.aideep.domain.node.dto.event.NodeEventEnvelope;

public record NodeEventWorkResult(Status status, NodeEventEnvelope nodeEventEnvelope, String eventId,
                                  String eventType, String errorCode, Throwable cause) {
    public static NodeEventWorkResult processed(NodeEventEnvelope nodeEventEnvelope) {
        return success(Status.PROCESSED, nodeEventEnvelope);
    }

    public static NodeEventWorkResult deferred(NodeEventEnvelope nodeEventEnvelope) {
        return success(Status.DEFERRED, nodeEventEnvelope);
    }

    public static NodeEventWorkResult permanentFailure(NodeEventEnvelope nodeEventEnvelope, String eventId,
                                                       String eventType, String errorCode) {
        return new NodeEventWorkResult(Status.PERMANENT_FAILURE, nodeEventEnvelope, eventId, eventType, errorCode,
                null);
    }

    public static NodeEventWorkResult retryableFailure(NodeEventEnvelope nodeEventEnvelope, String errorCode,
                                                       Throwable cause) {
        return new NodeEventWorkResult(Status.RETRYABLE_FAILURE, nodeEventEnvelope,
                nodeEventEnvelope == null ? null : nodeEventEnvelope.eventId().toString(),
                nodeEventEnvelope == null ? null : nodeEventEnvelope.eventType().name(), errorCode, cause);
    }

    private static NodeEventWorkResult success(Status status, NodeEventEnvelope nodeEventEnvelope) {
        return new NodeEventWorkResult(status, nodeEventEnvelope, nodeEventEnvelope.eventId().toString(),
                nodeEventEnvelope.eventType().name(), null, null);
    }

    public enum Status {
        PROCESSED,
        DEFERRED,
        PERMANENT_FAILURE,
        RETRYABLE_FAILURE
    }
}
