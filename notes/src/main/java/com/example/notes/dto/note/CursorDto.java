package com.example.notes.dto.note;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Represents a note returned by the NotesTogether service")
public record CursorDto (
        @Schema(
                description = "collaborators cursor position",
                example = "4"
        )
        int position
)
{}