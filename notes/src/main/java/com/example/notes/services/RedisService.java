package com.example.notes.services;

import com.example.notes.dto.note.NoteDto;
import com.example.notes.dto.noteVersion.NoteVersionDto;

import java.util.Map;
import java.util.UUID;

public interface RedisService {
    void initializeNote(String actorEmail, UUID noteId);
    void updateNote(NoteDto note, NoteVersionDto noteVersion);
    NoteDto getNote(UUID noteId);
    void deleteNote(UUID noteId);

    NoteVersionDto getNoteVersion(UUID noteId);
    int getInitialRevision(UUID noteId);

    void addCollaboratorToNote(UUID noteId, String actorEmail);
    void removeCollaboratorFromNote(UUID noteId, String actorEmail);
    Map<Object, Object> getCollaborators(UUID noteId);

    void setReviewInProgress(UUID noteId, String ownerEmail, String value);
    boolean isReviewInProgress(UUID noteId, String ownerEmail);
}
