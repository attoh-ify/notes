package com.example.notes.dto.note;

import com.example.notes.dto.ot.Delta;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Represents a data required to create a new note")
public record JoinNoteResponse (
    @Schema(
            description = "List of collaborators on the note"
    )
    List<String> collaborators,

    @Schema(
            description = "Delta of the most current note for Quill"
    )
    Delta delta,

    @Schema(
            description = "Number pointing to the current state of the server"
    )
    int revision
) {}