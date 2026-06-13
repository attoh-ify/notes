package com.crowninteractive.notes.dto.note;

import com.crowninteractive.notes.dto.ot.Delta;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Represents a data required to create a new note")
public record CreateNotePayload(
        @Schema(description = "Title of the note", example = "Project Meeting Notes")
        @NotBlank(message = "title is required")
        String title,

        @Schema(description = "Optional initial content as Quill Delta")
        Delta initialDelta
) {}