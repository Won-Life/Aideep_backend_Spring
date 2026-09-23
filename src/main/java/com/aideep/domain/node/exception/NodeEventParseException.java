package com.aideep.domain.node.exception;

public class NodeEventParseException extends RuntimeException {
    private final String errorCode;
    private final String eventId;
    private final String eventType;

    public NodeEventParseException(String errorCode, String message, String eventId, String eventType) {
        super(message);
        this.errorCode = errorCode;
        this.eventId = eventId;
        this.eventType = eventType;
    }

    public NodeEventParseException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.eventId = null;
        this.eventType = null;
    }

    public String errorCode() {
        return errorCode;
    }

    public String eventId() {
        return eventId;
    }

    public String eventType() {
        return eventType;
    }
}
