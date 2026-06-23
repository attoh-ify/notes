package com.crowninteractive.notes.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "Generic response wrapper for API endpoints")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ResponseDto {
        @Schema(description = "Indicates whether the request was successful", example = "true")
        private boolean status;

        @Schema(description = "A human-readable message describing the response", example = "Operation completed successfully")
        private String message;

        @Schema(description = "The payload of the response, can be any type or null")
        private Object data;

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