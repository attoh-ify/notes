package com.crowninteractive.notes.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Generic response wrapper for API endpoints")
public record ResponseDto(
        @Schema(description = "Indicates whether the request was successful", example = "true")
        boolean status,

        @Schema(description = "A human-readable message describing the response", example = "Operation completed successfully")
        String message,

        @Schema(description = "The payload of the response, can be any type or null")
        Object data
) {
    public ResponseDto(String message, Object data) {
        this(true, message, data);
    }

    public ResponseDto(boolean status, String message) {
        this(status, message, null);
    }

    public ResponseDto(String message) {
        this(true, message, null);
    }
}