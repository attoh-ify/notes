package com.example.notes.services;

import com.example.notes.dto.note.CreateNotePayload;
import com.example.notes.dto.note.NoteDto;
import com.example.notes.entities.note.NoteVisibility;

import java.util.List;
import java.util.UUID;

public interface NoteService {
    List<NoteDto> fetchNotes(String actorEmail);
    NoteDto fetchNote(String actorEmail, UUID noteId);
    NoteDto createNote(String actorEmail, CreateNotePayload payload);
    Object joinNote(UUID userId, String actorEmail, UUID noteId);
    void saveNote(String actorEmail, UUID noteId);
    void deleteNote(String actorEmail, UUID noteId);
    void changeNoteVisibility(String userEmail, UUID noteId, NoteVisibility visibility);
}
