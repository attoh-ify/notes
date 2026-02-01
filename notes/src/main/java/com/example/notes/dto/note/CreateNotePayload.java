package com.example.notes.dto.note;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Represents a data required to create a new note")
public record CreateNotePayload(
        @Schema(
                description = "Title of the note",
                example = "Project Meeting Notes"
        )
        String title
) {}