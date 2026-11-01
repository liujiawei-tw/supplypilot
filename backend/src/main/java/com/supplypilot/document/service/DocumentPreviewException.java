package com.supplypilot.document.service;

/** Internal failure raised when stored preview content is malformed or unsupported. */
public class DocumentPreviewException extends RuntimeException {
    public DocumentPreviewException(String message) {
        super(message);
    }

    public DocumentPreviewException(String message, Throwable cause) {
        super(message, cause);
    }
}
