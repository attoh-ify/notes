package com.example.notes.dto.note;

import com.example.notes.dto.ot.TextOperation;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Represents a payload required to review a note")
public record ReviewNotePayload(
        @Schema(
                description = "Single text operation representing all the rejected changes"
        )
        TextOperation rejectedChange,

        @Schema(
                description = "list of accepted text operations"
        )
        List<OpReferenceResponse> acceptedReferences
) {
}
