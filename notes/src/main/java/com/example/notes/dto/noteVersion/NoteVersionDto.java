package com.example.notes.dto.noteVersion;

import com.example.notes.dto.ot.Delta;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Represents a specific version of a note")
public record NoteVersionDto(
        @Schema(
                description = "Unique identifier of the note version",
                example = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
        )
        UUID id,

        @Schema(
                description = "Quill Delta for the note for this version"
        )
        Delta masterDelta,

        @Schema(
                description = ""
        )
        int revision,

        @Schema(
                description = "Identifier of the user who created this version",
                example = "3a6d8e27-4c2f-4b71-9e4a-91c9d2f5c611"
        )
        UUID createdBy,

        @Schema(
                description = "Version number of the note, starting from 1",
                example = "2"
        )
        Integer versionNumber,

        @Schema(
                description = "Timestamp when this version was created",
                example = "2026-01-07T11:45:00"
        )
        LocalDateTime createdAt
) {}