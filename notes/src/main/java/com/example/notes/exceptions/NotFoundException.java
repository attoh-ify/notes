package com.example.notes.exceptions;

public class NotFoundException extends ApiException {
    public NotFoundException(String message) {
        super(message);
    }
}
