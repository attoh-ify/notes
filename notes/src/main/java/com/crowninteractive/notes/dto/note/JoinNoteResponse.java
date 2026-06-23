package com.crowninteractive.notes.dto.note;

import com.crowninteractive.notes.dto.ot.Delta;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

@Schema(description = "Represents a data required to create a new note")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class JoinNoteResponse {
    @Schema(
            description = "List of collaborators on the note"
    )
    private Map<Object, Object> collaborators;

    @Schema(
            description = "Delta of the most current note for Quill"
    )
    private Delta delta;

    @Schema(
            description = "Number pointing to the current state of the server"
    )
    private int revision;

    @Schema(
            description = "Boolean indicating if the owner of the note is currently reviewing it"
    )
    private boolean isReviewing;

    private CollaborationMode mode;

    private int activeSessionCount;
}