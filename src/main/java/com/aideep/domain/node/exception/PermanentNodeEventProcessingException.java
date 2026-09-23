package com.aideep.domain.node.exception;

public class PermanentNodeEventProcessingException extends RuntimeException {
    private final String errorCode;

    public PermanentNodeEventProcessingException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
