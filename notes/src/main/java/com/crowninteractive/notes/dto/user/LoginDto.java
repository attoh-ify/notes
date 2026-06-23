package com.crowninteractive.notes.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "Request body for user login")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class LoginDto {
        @Email(message = "email is required")
        @Schema(description = "User's email address", example = "user@example.com")
        private String email;

        @NotBlank(message = "password is required")
        @Schema(description = "User's password", example = "StrongPassword123!")
        private String password;
}