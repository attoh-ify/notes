package com.example.notes.controllers;

import com.example.notes.dto.message_payload.CollaborationCountPayload;
import com.example.notes.dto.note.DocumentModel;
import com.example.notes.dto.note.NoteDto;
import com.example.notes.dto.response.ResponseDto;
import com.example.notes.entities.note.NoteVisibility;
import com.example.notes.entities.user.UserPrincipal;
import com.example.notes.notifier.CollaboratorCountNotifier;
import com.example.notes.security.CurrentUser;
import com.example.notes.services.NoteService;
import com.example.notes.shared.document_store.NoteStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/notes")
@Tag(
        name = "Notes",
        description = "Manage Notes"
)
public class NoteController {
    @Autowired
    private NoteStore noteStore;

    @Autowired
    private CollaboratorCountNotifier collaboratorCountNotifier;

    private final NoteService noteService;

    public NoteController(NoteService noteService) {
        this.noteService = noteService;
    }

    @PostMapping
    @Operation(summary = "Create a new note", description = "Create a new note for user")
    public ResponseDto createNote(
            @CurrentUser UserPrincipal currentUser
    ) {
        NoteDto note = noteService.createNote(currentUser.getEmail());

        System.out.println("Creating document " + note.id());
        noteStore.addEmptyNote(note.userId(), note.id());

        return new ResponseDto("Note created", note);
    }

    @GetMapping("/{noteId}/join/{userId}")
    public ResponseDto joinDoc(@PathVariable UUID noteId, @PathVariable UUID userId) {
        if (noteStore.getNoteFromNoteId(noteId) == null) {
            noteStore.addEmptyNote(userId, noteId);
        }
        DocumentModel doc = noteStore.getNoteFromNoteId(noteId);

        noteStore.addCollaboratorToNote(userId, noteId);
        System.out.println("JD count: " + doc.getCollaboratorCount());
        collaboratorCountNotifier.notifyCount(noteId, new CollaborationCountPayload(doc.getCollaboratorCount()));

        return new ResponseDto(
                true, "User joined the note successfully",
                Map.of(
                    "collaboratorCount", doc.getCollaboratorCount(),
                    "text", doc.getDocText(),
                    "revision", doc.getRevision()
                )
        );
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
        System.out.println("Fetching all notes for user " + currentUser.getEmail());
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
