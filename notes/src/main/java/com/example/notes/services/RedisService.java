package com.example.notes.services;

import com.example.notes.dto.note.CollaborationMode;
import com.example.notes.dto.note.NoteDto;
import com.example.notes.dto.noteVersion.NoteVersionDto;
import com.example.notes.dto.ot.TextOperation;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface RedisService {
    void initializeNote(String actorEmail, UUID noteId);
    void updateNote(NoteDto note, NoteVersionDto noteVersion);
    NoteDto getNote(UUID noteId);
    void deleteNote(UUID noteId);
    void refreshNoteContent(String actorEmail, UUID noteId);

    NoteVersionDto getNoteVersion(UUID noteId);
    int getInitialRevision(UUID noteId);
    void setInitialRevision(UUID noteId, int revision);

    void addCollaboratorToNote(UUID noteId, String actorEmail);
    void removeCollaboratorFromNote(UUID noteId, String actorEmail);
    Map<Object, Object> getCollaborators(UUID noteId);

    void addCollaboratorSession(UUID noteId, String actorEmail, String sessionId);
    boolean removeCollaboratorSession(UUID noteId, String sessionId);

    void markNoteDirty(UUID noteId);
    Set<UUID> getDirtyNotesDueForPersistence();
    boolean tryAcquirePersistenceLock(UUID noteId, String owner);
    void releasePersistenceLock(UUID noteId, String owner);
    void clearDirtyNoteIfRevisionUnchanged(UUID noteId, int persistedRevision);

    boolean tryAcquireOperationLock(UUID noteId, String owner, long ttlSeconds);
    void releaseOperationLock(UUID noteId, String owner);

    TextOperation getProcessedOperation(UUID noteId, String opId);
    void saveProcessedOperation(UUID noteId, TextOperation operation);

    void compactTransformRevisionLogIfNeeded(UUID noteId, NoteDto note);
    void appendPendingHistoryOperation(UUID noteId, TextOperation operation);
    List<TextOperation> getPendingHistoryOperations(UUID noteId);
    void clearPendingHistoryOperationsUpToRevision(UUID noteId, int savedRevision);

    void refreshCollaboratorSessionHeartbeat(UUID noteId, String sessionId, String actorEmail);
    boolean collaboratorSessionHeartbeatExists(UUID noteId, String sessionId);
    Set<UUID> getActiveCollaborationNoteIds();
    void cleanupStaleCollaboratorSessions(UUID noteId);

    int getActiveSessionCount(UUID noteId);
    boolean isCollaborativeMode(UUID noteId);
    CollaborationMode getCollaborationMode(UUID noteId);
}
