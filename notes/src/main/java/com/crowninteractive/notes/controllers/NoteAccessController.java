package com.crowninteractive.notes.controllers;

import com.crowninteractive.notes.dto.noteAccess.NoteAccessDto;
import com.crowninteractive.notes.dto.noteAccess.NoteAccessPayload;
import com.crowninteractive.notes.dto.response.ResponseDto;
import com.crowninteractive.notes.entities.user.UserPrincipal;
import com.crowninteractive.notes.security.CurrentUser;
import com.crowninteractive.notes.services.NoteAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/notes/{noteId}/access")
@Tag(
        name = "Note Accesses",
        description = "Manage Note Access"
)
public class NoteAccessController {
    private final NoteAccessService noteAccessService;

    public NoteAccessController(
            NoteAccessService noteAccessService
    ) {
        this.noteAccessService = noteAccessService;
    }

    @PostMapping
    @Operation(summary = "Add note access", description = "Grants access to a note to another user")
    public ResponseDto addAccess(
            @Valid
            @CurrentUser UserPrincipal currentUser,

            @Parameter(description = "Unique identifier of the note", required = true)
            @NotBlank(message = "Note ID is required")
            @PathVariable String noteId,

            @Parameter(description = "Unique identifier of the note", required = true)
            @RequestBody NoteAccessPayload payload
    ) {
        NoteAccessDto access = noteAccessService.addAccess(currentUser.getEmail(), noteId, payload);
        return new ResponseDto("Access added", access);
    }

    @PutMapping("/{noteAccessId}")
    @Operation(summary = "Update note access", description = "Updates access role for a note")
    public ResponseDto updateAccess(
            @Valid
            @CurrentUser UserPrincipal currentUser,

            @Parameter(description = "Unique identifier of the note", required = true)
            @NotBlank(message = "Note ID is required")
            @PathVariable String noteId,

            @Parameter(description = "Unique identifier of the note access", required = true)
            @NotBlank(message = "Note Access ID is required")
            @PathVariable String noteAccessId,

            @Parameter(description = "Note access details")
            @RequestBody NoteAccessPayload payload
    ) {
        NoteAccessDto access = noteAccessService.updateAccess(currentUser.getEmail(), noteId, noteAccessId, payload);
        return new ResponseDto("Access updated", access);
    }

    @DeleteMapping("/{noteAccessId}")
    @Operation(summary = "Delete note access", description = "Removes a user's access to a note")
    public ResponseDto deleteAccess(
            @CurrentUser UserPrincipal currentUser,

            @Parameter(description = "Unique identifier of the note", required = true)
            @NotBlank(message = "Note ID is required")
            @PathVariable String noteId,

            @Parameter(description = "Unique identifier of the note access", required = true)
            @NotBlank(message = "Note Access ID is required")
            @PathVariable String noteAccessId
    ) {
        noteAccessService.deleteAccess(currentUser.getEmail(), noteId, noteAccessId);
        return new ResponseDto("Access deleted", null);
    }

    @GetMapping
    @Operation(summary = "List note accesses", description = "Lists all users with access to a note")
    public ResponseDto getAccessList(
            @CurrentUser UserPrincipal currentUser,

            @Parameter(description = "Unique identifier of the note", required = true)
            @NotBlank(message = "Note ID is required")
            @PathVariable String noteId
    ) {
        List<NoteAccessDto> accesses = noteAccessService.getAllAccess(currentUser.getEmail(), noteId);
        return new ResponseDto("Access list fetched", accesses);
    }
}
