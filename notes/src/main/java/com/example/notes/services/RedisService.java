package com.example.notes.services;

import com.example.notes.entities.note.Note;
import com.example.notes.entities.noteVersion.NoteVersion;

import java.util.List;
import java.util.UUID;

public interface RedisService {
    void initializeNote(String actorEmail, UUID noteId);
    void updateNote(String actorEmail, UUID noteId, Note note, NoteVersion noteVersion);
    Note getNote(String actorEmail, UUID noteId);
    void deleteNote(String actorEmail, UUID noteId);

    NoteVersion getNoteVersion(String actorEmail, UUID noteId);

    void addCollaboratorToNote(UUID noteId, String actorEmail);
    void removeCollaboratorFromNote(UUID noteId, String actorEmail);
    List<String> getCollaborators(String actorEmail, UUID noteId);
}
