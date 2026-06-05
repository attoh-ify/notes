package com.example.notes.controllers;

import com.example.notes.dto.attribution.ReviewProjection;
import com.example.notes.dto.note.*;
import com.example.notes.dto.response.ResponseDto;
import com.example.notes.entities.note.NoteVisibility;
import com.example.notes.entities.user.UserPrincipal;
import com.example.notes.security.CurrentUser;
import com.example.notes.services.NoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/notes")
@Tag(
        name = "Notes",
        description = "Manage Notes"
)
public class NoteController {
    private final NoteService noteService;

    public NoteController(NoteService noteService) {
        this.noteService = noteService;
    }

    @PostMapping
    @Operation(summary = "Create a new note", description = "Create a new note for user")
    public ResponseDto createNote(
            @CurrentUser UserPrincipal currentUser,
            @RequestBody CreateNotePayload payload
    ) {
        NoteDto note = noteService.createNote(currentUser.getEmail(), payload);
        return new ResponseDto("Note created", note);
    }

    @GetMapping("/{noteId}/join")
    public ResponseDto joinDoc(@CurrentUser UserPrincipal currentUser, @PathVariable UUID noteId) {
        JoinNoteResponse response = noteService.joinNote(currentUser.getUserId(), currentUser.getEmail(), noteId);
        return new ResponseDto(
                true, response != null ? "User joined the note successfully" : "Note is currently under review",
                response
        );
    }

    @GetMapping("/{noteId}/build-attribution")
    public ResponseDto buildAttribution(@CurrentUser UserPrincipal currentUser, @PathVariable UUID noteId) throws Exception {
        ReviewProjection reviewProjection = noteService.buildAttribution(currentUser.getEmail(), noteId);
        return new ResponseDto(true, "ok", reviewProjection);
    }

    @GetMapping("/{noteId}/review")
    public ResponseDto startReview(@CurrentUser UserPrincipal currentUser, @PathVariable UUID noteId) throws Exception {
        noteService.startReview(currentUser.getEmail(), noteId);
        return new ResponseDto("ok");
    }

    @PostMapping("/{noteId}/review")
    public ResponseDto applyReviewChanges(@CurrentUser UserPrincipal currentUser, @PathVariable UUID noteId, @RequestBody ReviewNotePayload payload) {
        noteService.applyReviewChanges(currentUser.getEmail(), noteId, payload);
        return new ResponseDto("ok");
    }

    @GetMapping("/{noteId}/review/exit")
    public ResponseDto exitReview(@CurrentUser UserPrincipal currentUser, @PathVariable UUID noteId) throws Exception {
        noteService.exitReviewNote(currentUser.getEmail(), noteId);
        return new ResponseDto("ok");
    }

    @PostMapping("/{noteId}/save")
    @Operation(summary = "Save note", description = "Saves current note version with new note content")
    public ResponseDto saveNote(@CurrentUser UserPrincipal currentUser, @PathVariable UUID noteId) {
        noteService.saveNote(currentUser.getEmail(), noteId);
        return new ResponseDto(true, "Note saved", null);
    }

    @GetMapping
    @Operation(summary = "Fetch all notes", description = "Retrieves all notes accessible to the user")
    public ResponseDto getAllNotes(
            @CurrentUser UserPrincipal currentUser
    ) {
        List<NoteDto> notes = noteService.fetchNotes(currentUser.getEmail());
        return new ResponseDto("Notes fetched", notes);
    }

    @GetMapping("/{noteId}")
    @Operation(summary = "Fetch a single note", description = "Retrieves a specific note by ID")
    public ResponseDto getNote(
            @CurrentUser UserPrincipal currentUser,

            @Parameter(description = "Unique identifier of the note", required = true)
            @PathVariable UUID noteId
    ) {
        NoteDto note = noteService.fetchNote(currentUser.getEmail(), noteId);
        return new ResponseDto("Note fetched", note);
    }

    @DeleteMapping("/{noteId}")
    @Operation(summary = "Delete a note", description = "Deletes a note by ID")
    public ResponseDto deleteNote(
            @CurrentUser UserPrincipal currentUser,

            @Parameter(description = "Unique identifier of the note", required = true)
            @PathVariable UUID noteId
    ) {
        noteService.deleteNote(currentUser.getEmail(), noteId);
        return new ResponseDto("Note deleted", null);
    }

    @PutMapping("/{noteId}/visibility")
    @Operation(summary = "Change note visibility", description = "Changes the visibility of a note (PUBLIC/PRIVATE)")
    public ResponseDto changeVisibility(
            @CurrentUser UserPrincipal currentUser,

            @Parameter(description = "Unique identifier of the note", required = true)
            @PathVariable UUID noteId,

            @Parameter(description = "New visibility status", required = true)
            @RequestParam NoteVisibility visibility
    ) {
        noteService.changeNoteVisibility(currentUser.getEmail(), noteId, visibility);
        return new ResponseDto("Note visibility updated", visibility);
    }
}
