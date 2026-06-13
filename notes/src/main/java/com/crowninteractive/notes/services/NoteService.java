package com.crowninteractive.notes.services;

import com.crowninteractive.notes.dto.note.CreateNotePayload;
import com.crowninteractive.notes.dto.note.JoinNoteResponse;
import com.crowninteractive.notes.dto.note.NoteDto;
import com.crowninteractive.notes.dto.ot.TextOperation;
import com.crowninteractive.notes.entities.note.NoteVisibility;

import java.util.List;

public interface NoteService {
    List<NoteDto> fetchNotes(String actorEmail);
    NoteDto fetchNote(String actorEmail, String noteId);
    NoteDto createNote(String actorEmail, CreateNotePayload payload);
    JoinNoteResponse joinNote(String userId, String actorEmail, String noteId);
    void buildAttribution(String actorEmail, String noteId);
    void startReview(String actorEmail, String noteId);
    void exitReviewNote(String actorEmail, String noteId);
    void saveNote(String actorEmail, String noteId);
    void deleteNote(String actorEmail, String noteId);
    void changeNoteVisibility(String userEmail, String noteId, NoteVisibility visibility);
    int soloSyncFromJoinedSession(String actorEmail, String noteId, TextOperation operation);
}
