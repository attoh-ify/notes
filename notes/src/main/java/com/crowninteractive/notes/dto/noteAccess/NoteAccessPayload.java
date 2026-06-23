package com.crowninteractive.notes.dto.noteAccess;

import com.crowninteractive.notes.entities.noteAccess.NoteAccessRole;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;

@Schema(description = "Represents payload required to add and update access to users note")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class NoteAccessPayload {
        @Email(message = "Invalid email")
        @NotBlank(message = "email is required")
        @Schema(
                description = "Email of the user you want to give access to the note",
                example = "user@example.com"
        )
        private String email;

        @NotBlank
        @Schema(
                description = "Role of the user for this note, determining their permissions",
                example = "EDITOR"
        )
        private NoteAccessRole role;
}
