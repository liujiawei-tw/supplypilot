package com.supplypilot.document.rag;

/** Stops the Advisor chain before ChatModel invocation when no source survives backend checks. */
final class NoVerifiedRagEvidenceException extends RuntimeException {
    NoVerifiedRagEvidenceException() {
        super("No verified RAG evidence is available");
    }
}
