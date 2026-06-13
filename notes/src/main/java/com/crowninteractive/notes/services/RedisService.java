package com.crowninteractive.notes.services;

import com.crowninteractive.notes.dto.note.CollaborationMode;
import com.crowninteractive.notes.dto.note.NoteDto;
import com.crowninteractive.notes.dto.noteVersion.NoteVersionDto;
import com.crowninteractive.notes.dto.ot.TextOperation;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface RedisService {
    void initializeNote(String actorEmail, String noteId);
    void updateNote(NoteDto note, NoteVersionDto noteVersion);
    NoteDto getNote(String noteId);
    void deleteNote(String noteId);
    void refreshNoteContent(String actorEmail, String noteId);

    NoteVersionDto getNoteVersion(String noteId);
    int getInitialRevision(String noteId);
    void setInitialRevision(String noteId, int revision);

    void addCollaboratorToNote(String noteId, String actorEmail);
    void removeCollaboratorFromNote(String noteId, String actorEmail);
    Map<Object, Object> getCollaborators(String noteId);

    void addCollaboratorSession(String noteId, String actorEmail, String sessionId);
    boolean removeCollaboratorSession(String noteId, String sessionId);

    void markNoteDirty(String noteId);
    Set<String> getDirtyNotesDueForPersistence();
    boolean tryAcquirePersistenceLock(String noteId, String owner);
    void releasePersistenceLock(String noteId, String owner);
    void clearDirtyNoteIfRevisionUnchanged(String noteId, int persistedRevision);

    boolean tryAcquireOperationLock(String noteId, String owner, long ttlSeconds);
    void releaseOperationLock(String noteId, String owner);

    TextOperation getProcessedOperation(String noteId, String opId);
    void saveProcessedOperation(String noteId, TextOperation operation);

    void compactTransformRevisionLogIfNeeded(String noteId, NoteDto note);
    void appendPendingHistoryOperation(String noteId, TextOperation operation);
    List<TextOperation> getPendingHistoryOperations(String noteId);
    void clearPendingHistoryOperationsUpToRevision(String noteId, int savedRevision);

    void refreshCollaboratorSessionHeartbeat(String noteId, String sessionId, String actorEmail);
    boolean collaboratorSessionHeartbeatExists(String noteId, String sessionId);
    Set<String> getActiveCollaborationNoteIds();
    void cleanupStaleCollaboratorSessions(String noteId);

    int getActiveSessionCount(String noteId);
    boolean isCollaborativeMode(String noteId);
    CollaborationMode getCollaborationMode(String noteId);
}
