package com.example.notes.controllers;

import com.example.notes.dto.attribution.ReviewProjection;
import com.example.notes.dto.noteVersion.CreateNoteVersionPayload;
import com.example.notes.dto.noteVersion.NoteVersionDto;
import com.example.notes.dto.response.ResponseDto;
import com.example.notes.entities.user.UserPrincipal;
import com.example.notes.security.CurrentUser;
import com.example.notes.services.NoteVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

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
            @CurrentUser UserPrincipal currentUser,

            @Parameter(description = "Unique identifier of the note", required = true)
            @PathVariable UUID noteId
    ) {
        List<NoteVersionDto> versions = noteVersionService.fetchAllVersions(currentUser.getEmail(), noteId);
        return new ResponseDto("Note versions fetched", versions);
    }

    @GetMapping("/{versionNumber}")
    @Operation(summary = "Fetch a specific note version", description = "Retrieves a specific version of a note")
    public ResponseDto getVersion(
            @CurrentUser UserPrincipal currentUser,

            @Parameter(description = "Unique identifier of the note", required = true)
            @PathVariable UUID noteId,

            @Parameter(description = "Version number of the note version", required = true)
            @PathVariable int versionNumber
    ) {
        NoteVersionDto version = noteVersionService.fetchVersion(currentUser.getEmail(), noteId, versionNumber);
        return new ResponseDto("Note version fetched", version);
    }

    @PostMapping
    @Operation(summary = "Create a note version", description = "Create a new note version")
    public ResponseDto createVersion(
            @CurrentUser UserPrincipal currentUser,

            @Parameter(description = "Unique identifier of the note", required = true)
            @PathVariable UUID noteId,

            @Parameter(description = "Object of the note version", required = true)
            @RequestBody CreateNoteVersionPayload payload
    ) {
        NoteVersionDto newNoteVersion = noteVersionService.createVersion(currentUser.getEmail(), noteId, payload);
        return new ResponseDto("Note restored to version", newNoteVersion);
    }

    @PutMapping("/{versionId}/restore")
    @Operation(summary = "Restore a note version", description = "Restores a note to a previous version")
    public ResponseDto restoreVersion(
            @CurrentUser UserPrincipal currentUser,

            @Parameter(description = "Unique identifier of the note", required = true)
            @PathVariable UUID noteId,

            @Parameter(description = "Unique identifier of the note version", required = true)
            @PathVariable UUID versionId
    ) {
        NoteVersionDto restored = noteVersionService.restoreVersion(currentUser.getEmail(), noteId, versionId);
        return new ResponseDto("Note restored to version", restored);
    }

    @GetMapping("/{versionId}/audit")
    public ResponseDto auditVersions(@CurrentUser UserPrincipal currentUser, @PathVariable UUID noteId, @PathVariable UUID versionId) throws Exception {
        ReviewProjection reviewProjection = noteVersionService.auditVersion(currentUser.getEmail(), noteId, versionId);
        return new ResponseDto(true, "ok", reviewProjection);
    }
}
