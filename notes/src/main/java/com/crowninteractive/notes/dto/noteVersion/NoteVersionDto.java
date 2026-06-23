package com.crowninteractive.notes.dto.noteVersion;

import com.crowninteractive.notes.dto.ot.Delta;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Schema(description = "Represents a specific version of a note")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class NoteVersionDto {
        private Long id;

        @Schema(
                description = "Unique identifier of the note version",
                example = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
        )
        private String noteVersionId;

        @Schema(
                description = "Quill Delta for the note for this version"
        )
        private Delta masterDelta;

        @Schema(
                description = ""
        )
        private int revision;

        @Schema(
                description = "Description to easily remember what version this is",
                example = "Changed Introduction paragraph"
        )
        private String comment;

        @Schema(
                description = "Version number of the note, starting from 1",
                example = "2"
        )
        private Integer versionNumber;

        @Schema(
                description = "Timestamp when this version was created",
                example = "2026-01-07T11:45:00"
        )
        private LocalDateTime createdAt;
}