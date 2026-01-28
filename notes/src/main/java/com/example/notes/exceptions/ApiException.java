package com.example.notes.exceptions;

public abstract class ApiException extends RuntimeException {
    protected ApiException(String message) {
        super(message);
    }
}
