package com.example.notes.dto.note;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Represents a note returned by the NotesTogether service")
public record SplitOpPayload(
        @Schema(
                description = "Unique identifier of the text operation",
                example = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
        )
        String insertOpId,

        @Schema(
                description = "Unique identifier of the text operation",
                example = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
        )
        String deleteOpId,

        int insertOpLength,

        int overlapLength,

        int deleteOpTotalLength,

        int deleteConsumedBefore
) {
}
