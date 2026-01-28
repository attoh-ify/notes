package com.example.notes.dto.noteAccess;

import com.example.notes.entities.noteAccess.NoteAccessRole;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Represents access rights of a user to a specific note")
public record NoteAccessDto(
        @Schema(
                description = "Unique identifier of the note access entry",
                example = "d290f1ee-6c54-4b01-90e6-d701748f0851"
        )
        UUID id,

        @Schema(
                description = "Email of the user who gave access access to the note",
                example = "user@example.com"
        )
        String email,

        @Schema(
                description = "Role of the user for this note, determining their permissions",
                example = "EDITOR"
        )
        NoteAccessRole role
) {}
