package com.crowninteractive.notes.controllers;

import com.crowninteractive.notes.dto.note.CreateNotePayload;
import com.crowninteractive.notes.dto.note.JoinNoteResponse;
import com.crowninteractive.notes.dto.note.NoteDto;
import com.crowninteractive.notes.dto.response.ResponseDto;
import com.crowninteractive.notes.entities.note.NoteVisibility;
import com.crowninteractive.notes.entities.user.UserPrincipal;
import com.crowninteractive.notes.security.CurrentUser;
import com.crowninteractive.notes.services.NoteService;
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
            @Valid
            @CurrentUser UserPrincipal currentUser,

            @RequestBody CreateNotePayload payload
    ) {
        NoteDto note = noteService.createNote(currentUser.getEmail(), payload);
        return new ResponseDto("Note created", note);
    }

    @GetMapping("/{noteId}/join")
    public ResponseDto joinDoc(
            @Valid
            @CurrentUser UserPrincipal currentUser,

            @PathVariable
            @NotBlank(message = "Note ID is required")
            String noteId
    ) {
        JoinNoteResponse response = noteService.joinNote(currentUser.getUserId(), currentUser.getEmail(), noteId);
        return new ResponseDto(
                true, response != null ? "User joined the note successfully" : "Note is currently under review",
                response
        );
    }

    @GetMapping("/{noteId}/review")
    public ResponseDto startReview(
            @Valid
            @CurrentUser UserPrincipal currentUser,

            @NotBlank(message = "Note ID is required")
            @PathVariable String noteId
    ) throws Exception {
        noteService.startReview(currentUser.getEmail(), noteId);
        return new ResponseDto("ok");
    }

    @GetMapping("/{noteId}/review/exit")
    public ResponseDto exitReview(
            @Valid
            @CurrentUser UserPrincipal currentUser,

            @NotBlank(message = "Note ID is required")
            @PathVariable String noteId
    ) throws Exception {
        noteService.exitReviewNote(currentUser.getEmail(), noteId);
        return new ResponseDto("ok");
    }

    @PostMapping("/{noteId}/save")
    @Operation(summary = "Save note", description = "Saves current note version with new note content")
    public ResponseDto saveNote(
            @Valid
            @CurrentUser UserPrincipal currentUser,

            @NotBlank(message = "Note ID is required")
            @PathVariable String noteId
    ) {
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
            @Valid
            @CurrentUser UserPrincipal currentUser,

            @Parameter(description = "Unique identifier of the note", required = true)
            @NotBlank(message = "Note ID is required")
            @PathVariable String noteId
    ) {
        NoteDto note = noteService.fetchNote(currentUser.getEmail(), noteId);
        return new ResponseDto("Note fetched", note);
    }

    @DeleteMapping("/{noteId}")
    @Operation(summary = "Delete a note", description = "Deletes a note by ID")
    public ResponseDto deleteNote(
            @Valid
            @CurrentUser UserPrincipal currentUser,

            @Parameter(description = "Unique identifier of the note", required = true)
            @NotBlank(message = "Note ID is required")
            @PathVariable String noteId
    ) {
        noteService.deleteNote(currentUser.getEmail(), noteId);
        return new ResponseDto("Note deleted", null);
    }

    @PutMapping("/{noteId}/visibility")
    @Operation(summary = "Change note visibility", description = "Changes the visibility of a note (PUBLIC/PRIVATE)")
    public ResponseDto changeVisibility(
            @Valid
            @CurrentUser UserPrincipal currentUser,

            @Parameter(description = "Unique identifier of the note", required = true)
            @NotBlank(message = "Note ID is required")
            @PathVariable String noteId,

            @Parameter(description = "New visibility status", required = true)
            @NotBlank(message = "visibility is required")
            @RequestParam NoteVisibility visibility
    ) {
        noteService.changeNoteVisibility(currentUser.getEmail(), noteId, visibility);
        return new ResponseDto("Note visibility updated", visibility);
    }
}
