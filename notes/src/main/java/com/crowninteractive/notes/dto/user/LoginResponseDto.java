package com.crowninteractive.notes.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response returned after a successful login")
public record LoginResponseDto(
        @Schema(description = "JWT token to be used for authenticated requests",
                example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
        String token,

        @Schema(description = "User id of the logged in user",
                example = "")
        String userId
) {}