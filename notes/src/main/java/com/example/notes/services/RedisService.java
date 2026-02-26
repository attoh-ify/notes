package com.example.notes.services;

import com.example.notes.entities.note.Note;
import com.example.notes.entities.noteVersion.NoteVersion;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface RedisService {
    void initializeNote(String actorEmail, UUID noteId);
    void updateNote(Note note, NoteVersion noteVersion);
    Note getNote(UUID noteId);
    void deleteNote(UUID noteId);

    NoteVersion getNoteVersion(UUID noteId);

    void addCollaboratorToNote(UUID noteId, String actorEmail);
    void removeCollaboratorFromNote(UUID noteId, String actorEmail);
    Map<Object, Object> getCollaborators(UUID noteId);
}
