package com.crowninteractive.notes.dto.noteVersion;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CreateNoteVersionPayload {
        @Schema(
                description = "Description to easily remember what version this is",
                example = "Changed Introduction paragraph"
        )
        private String comment;
}
