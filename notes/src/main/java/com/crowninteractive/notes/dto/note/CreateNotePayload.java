package com.crowninteractive.notes.dto.note;

import com.crowninteractive.notes.dto.ot.Delta;
import io.swagger.v3.oas.annotations.media.Schema;
import javax.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "Represents a data required to create a new note")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CreateNotePayload {
        @Schema(description = "Title of the note", example = "Project Meeting Notes")
        @NotBlank(message = "title is required")
        private String title;

        @Schema(description = "Optional initial content as Quill Delta")
        private Delta initialDelta;
}