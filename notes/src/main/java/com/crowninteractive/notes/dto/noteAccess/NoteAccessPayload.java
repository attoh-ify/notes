package com.crowninteractive.notes.dto.noteAccess;

import com.crowninteractive.notes.entities.noteAccess.NoteAccessRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Represents payload required to add and update access to users note")
public record NoteAccessPayload(
        @Email(message = "Invalid email")
        @NotBlank(message = "email is required")
        @Schema(
                description = "Email of the user you want to give access to the note",
                example = "user@example.com"
        )
        String email,

        @NotBlank
        @Schema(
                description = "Role of the user for this note, determining their permissions",
                example = "EDITOR"
        )
        NoteAccessRole role
) {}
