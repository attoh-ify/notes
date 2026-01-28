package com.example.notes.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request body for user login")
public record LoginDto(
        @Schema(description = "User's email address", example = "user@example.com")
        String email,

        @Schema(description = "User's password", example = "StrongPassword123!")
        String password
) {}