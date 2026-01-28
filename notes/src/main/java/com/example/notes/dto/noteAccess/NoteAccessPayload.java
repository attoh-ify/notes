package com.example.notes.dto.noteAccess;

import com.example.notes.entities.noteAccess.NoteAccessRole;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Represents payload required to add and update access to users note")
public record NoteAccessPayload(
        @Schema(
                description = "Email of the user you want to give access to the note",
                example = "user@example.com"
        )
        String email,

        @Schema(
                description = "Role of the user for this note, determining their permissions",
                example = "EDITOR"
        )
        NoteAccessRole role
) {}
