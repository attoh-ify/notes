package com.crowninteractive.notes.dto.note;

import com.crowninteractive.notes.dto.ot.TextOperation;
import com.crowninteractive.notes.entities.note.NoteVisibility;
import com.crowninteractive.notes.entities.noteAccess.NoteAccessRole;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Represents a note returned by the NotesTogether service")
public record NoteDto(
        Long id,

        @Schema(
                description = "Unique identifier of the note",
                example = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
        )
        String noteId,

        @Schema(
                description = "Email of the user that owns the note",
                example = "d290f1ee-6c54-4b01-90e6-d701748f0851"
        )
        String ownerEmail,

        @Schema(
                description = "Title of the note",
                example = "Project Meeting Notes"
        )
        String title,

        @Schema(
                description = "List of text operations that make up this note"
        )
        List<TextOperation> revisionLog,

        @Schema(
                description = "Visibility of the note, either private or public",
                example = "PRIVATE"
        )
        NoteVisibility visibility,

        @Schema(
                description = "Current users role on the note",
                example = "VIEWER"
        )
        NoteAccessRole accessRole,

        @Schema(
                description = "Current note version number"
        )
        int currentNoteVersionNumber,

        @Schema(
                description = "States if a note is being reviewed or not",
                example = "true"
        )
        Boolean isReviewing,

        @Schema(
                description = "Timestamp when the note was created",
                example = "2026-01-07T11:15:30"
        )
        LocalDateTime createdAt,

        @Schema(
                description = "Timestamp when the note was last updated",
                example = "2026-01-07T11:45:00"
        )
        LocalDateTime updatedAt
) {}