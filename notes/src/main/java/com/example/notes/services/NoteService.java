package com.example.notes.services;

import com.example.notes.dto.attribution.ReviewProjection;
import com.example.notes.dto.note.*;
import com.example.notes.dto.ot.TextOperation;
import com.example.notes.entities.note.NoteVisibility;

import java.util.List;
import java.util.UUID;

public interface NoteService {
    List<NoteDto> fetchNotes(String actorEmail);
    NoteDto fetchNote(String actorEmail, UUID noteId);
    List<TextOperation> fetchRevisionLog(String actorEmail, UUID noteId);
    NoteDto createNote(String actorEmail, CreateNotePayload payload);
    JoinNoteResponse joinNote(UUID userId, String actorEmail, UUID noteId);
    ReviewProjection buildAttribution(String actorEmail, UUID noteId);
    void changeCursor(CursorDto cursorDto, UUID noteId, String actorEmail);
    void startReview(String actorEmail, UUID noteId);
    void applyReviewChanges(String actorEmail, UUID noteId, ReviewNotePayload payload);
    void exitReviewNote(String actorEmail, UUID noteId);
    void saveNote(String actorEmail, UUID noteId);
    void deleteNote(String actorEmail, UUID noteId);
    void changeNoteVisibility(String userEmail, UUID noteId, NoteVisibility visibility);
}
