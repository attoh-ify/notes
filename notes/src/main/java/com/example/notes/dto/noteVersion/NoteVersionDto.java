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
                description = "Description to easily remember what version this is",
                example = "Changed Introduction paragraph"
        )
        String comment,

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