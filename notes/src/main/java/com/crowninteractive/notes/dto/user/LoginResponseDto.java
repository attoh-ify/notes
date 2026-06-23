package com.crowninteractive.notes.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "Response returned after a successful login")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponseDto {
        @Schema(description = "JWT token to be used for authenticated requests",
                example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
        private String token;

        @Schema(description = "User id of the logged in user",
                example = "")
        private String userId;
}