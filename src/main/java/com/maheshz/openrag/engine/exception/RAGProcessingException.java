package com.maheshz.openrag.engine.exception;

public class RAGProcessingException extends RuntimeException {
    public RAGProcessingException(String message) {
        super(message);
    }

    public RAGProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}