package com.supplypilot.document.rag;

public final class DocumentParsingException extends RuntimeException {
    DocumentParsingException(String message, Throwable cause) {
        super(message, cause);
    }

    DocumentParsingException(String message) {
        super(message);
    }
}
