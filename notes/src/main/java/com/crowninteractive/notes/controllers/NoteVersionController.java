package com.crowninteractive.notes.controllers;

import com.crowninteractive.notes.dto.attribution.AuditProjection;
import com.crowninteractive.notes.dto.noteVersion.CreateNoteVersionPayload;
import com.crowninteractive.notes.dto.noteVersion.NoteVersionDto;
import com.crowninteractive.notes.dto.response.ResponseDto;
import com.crowninteractive.notes.entities.user.UserPrincipal;
import com.crowninteractive.notes.security.CurrentUser;
import com.crowninteractive.notes.services.NoteVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/notes/{noteId}/versions")
@Tag(
        name = "Note Versions",
        description = "Manage Note Versions"
)
public class NoteVersionController {
    private final NoteVersionService noteVersionService;

    public NoteVersionController(
            NoteVersionService noteVersionService
    ) {
        this.noteVersionService = noteVersionService;
    }

    @GetMapping
    @Operation(summary = "Fetch all note versions", description = "Retrieves all versions of a note")
    public ResponseDto getAllVersions(
            @Valid
            @CurrentUser UserPrincipal currentUser,

            @Parameter(description = "Unique identifier of the note", required = true)
            @NotBlank(message = "Note ID is required")
            @PathVariable String noteId
    ) {
        List<NoteVersionDto> versions = noteVersionService.fetchAllVersions(currentUser.getEmail(), noteId);
        return new ResponseDto("Note versions fetched", versions);
    }

    @GetMapping("/{versionNumber}")
    @Operation(summary = "Fetch a specific note version", description = "Retrieves a specific version of a note")
    public ResponseDto getVersion(
            @Valid
            @CurrentUser UserPrincipal currentUser,

            @Parameter(description = "Unique identifier of the note", required = true)
            @NotBlank(message = "Note ID is required")
            @PathVariable String noteId,

            @Parameter(description = "Version number of the note version", required = true)
            @NotBlank(message = "Note ID is required")
            @PathVariable int versionNumber
    ) {
        NoteVersionDto version = noteVersionService.fetchVersion(currentUser.getEmail(), noteId, versionNumber);
        return new ResponseDto("Note version fetched", version);
    }

    @PostMapping
    @Operation(summary = "Create a note version", description = "Create a new note version")
    public ResponseDto createVersion(
            @Valid
            @CurrentUser UserPrincipal currentUser,

            @Parameter(description = "Unique identifier of the note", required = true)
            @NotBlank(message = "Note ID is required")
            @PathVariable String noteId,

            @Parameter(description = "Object of the note version", required = true)
            @RequestBody CreateNoteVersionPayload payload
    ) {
        NoteVersionDto newNoteVersion = noteVersionService.createVersion(currentUser.getEmail(), noteId, payload);
        return new ResponseDto("Note restored to version", newNoteVersion);
    }

    @PutMapping("/{versionId}/restore")
    @Operation(summary = "Restore a note version", description = "Restores a note to a previous version")
    public ResponseDto restoreVersion(
            @Valid
            @CurrentUser UserPrincipal currentUser,

            @Parameter(description = "Unique identifier of the note", required = true)
            @NotBlank(message = "Note ID is required")
            @PathVariable String noteId,

            @Parameter(description = "Unique identifier of the note version", required = true)
            @NotBlank(message = "Note Version ID is required")
            @PathVariable String versionId
    ) {
        NoteVersionDto restored = noteVersionService.restoreVersion(currentUser.getEmail(), noteId, versionId);
        return new ResponseDto("Note restored to version", restored);
    }

    @GetMapping("/{versionId}/audit")
    public ResponseDto auditVersions(
            @Valid
            @CurrentUser UserPrincipal currentUser,

            @NotBlank(message = "Note ID is required")
            @PathVariable
            String noteId,

            @NotBlank(message = "Note Version ID is required")
            @PathVariable
            String versionId
    ) throws Exception {
        AuditProjection auditProjection = noteVersionService.auditVersion(currentUser.getEmail(), noteId, versionId);
        return new ResponseDto(true, "ok", auditProjection);
    }
}
