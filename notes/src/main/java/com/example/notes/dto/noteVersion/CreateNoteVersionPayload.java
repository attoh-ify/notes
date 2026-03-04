package com.example.notes.dto.noteVersion;

import io.swagger.v3.oas.annotations.media.Schema;

public record CreateNoteVersionPayload(
        @Schema(
                description = "Description to easily remember what version this is",
                example = "Changed Introduction paragraph"
        )
        String comment
) {
}
