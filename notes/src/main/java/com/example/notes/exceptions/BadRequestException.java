package com.example.notes.exceptions;

public class BadRequestException extends ApiException {
    public BadRequestException(String message) {
        super(message);
    }
}
