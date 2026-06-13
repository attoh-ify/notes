package com.crowninteractive.notes.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request body for user login")
public record LoginDto(
        @Email(message = "email is required")
        @Schema(description = "User's email address", example = "user@example.com")
        String email,

        @NotBlank(message = "password is required")
        @Schema(description = "User's password", example = "StrongPassword123!")
        String password
) {}