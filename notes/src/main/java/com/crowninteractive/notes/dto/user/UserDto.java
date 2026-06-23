package com.crowninteractive.notes.dto.user;

import com.crowninteractive.notes.dto.note.NoteDto;
import io.swagger.v3.oas.annotations.media.Schema;
import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Represents a user in the Notes service")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class UserDto {
        private Long id;

        @Schema(
                description = "Unique identifier of the user",
                example = "c9b1f8a0-3d15-4a12-bd5a-7c0d0e7b2f1f"
        )
        private String userId;

        @Schema(
                description = "Email address of the user",
                example = "user@example.com"
        )
        @NotBlank(message = "email is required")
        @Email(message = "Invalid email")
        private String email;

        @Schema(
                description = "Password for authentication",
                example = "StrongP@ssw0rd"
        )
        @NotBlank(message = "password is required")
        private String password;

        @Schema(description = "List of notes owned by the user")
        private List<NoteDto> notes;

        @Schema(
                description = "Timestamp when the user was created",
                example = "2026-01-07T10:15:30"
        )
        private LocalDateTime createdAt;

        @Schema(
                description = "Timestamp when the user was last updated",
                example = "2026-01-07T11:00:00"
        )
        private LocalDateTime updatedAt;
}