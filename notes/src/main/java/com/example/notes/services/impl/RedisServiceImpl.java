package com.example.notes.services.impl;

import com.example.notes.dto.note.NoteDto;
import com.example.notes.dto.noteVersion.NoteVersionDto;
import com.example.notes.dto.ot.TextOperation;
import com.example.notes.entities.note.Note;
import com.example.notes.entities.noteVersion.NoteVersion;
import com.example.notes.services.RedisService;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

@Service
public class RedisServiceImpl implements RedisService {
    private static final Logger log =
            LoggerFactory.getLogger(RedisServiceImpl.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final NotePolicyService notePolicyService;

    private static final String DIRTY_NOTES_KEY = "dirty-notes";
    private static final String PERSIST_LOCK_PREFIX = "persist-lock:";
    private static final String OPERATION_LOCK_PREFIX = "operation-lock:";
    private static final long COLLABORATOR_SESSION_HEARTBEAT_TTL_SECONDS = 300;
    private static final String ACTIVE_COLLABORATION_NOTES_KEY = "active-collaboration-notes";
    private static final long LOCK_TTL_SECONDS = 120;
    private static final int BATCH_SIZE = 1000;
    private static final long DIRTY_AGE_MILLIS = 60_000;
    private static final String[] COLLABORATOR_COLORS = {
            "#1F3A93",
            "#D32F2F",
            "#00796B",
            "#F57C00",
            "#512DA8",
            "#C2185B",
            "#303F9F",
            "#388E3C",
            "#FBC02D",
            "#455A64",
            "#E64A19",
            "#5D4037",
            "#1976D2",
            "#7B1FA2",
            "#0097A7",
            "#AFB42B",
            "#6A1B9A",
            "#C62828",
            "#00838F",
            "#AD1457",
            "#283593",
            "#2E7D32",
            "#EF6C00",
            "#4A148C",
            "#00695C",
            "#8E24AA",
            "#B71C1C",
            "#1565C0",
            "#1B5E20",
            "#FF8F00",
            "#880E4F",
            "#0D47A1",
            "#004D40",
            "#6D4C41",
            "#37474F",
            "#E65100",
            "#311B92",
            "#827717",
            "#01579B",
            "#4E342E",
            "#1A237E",
            "#33691E",
            "#BF360C",
            "#3E2723",
            "#263238",
            "#F9A825",
            "#8D6E63",
            "#9E9D24",
            "#0288D1",
            "#D84315"

    };

    public RedisServiceImpl(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, NotePolicyService notePolicyService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.notePolicyService = notePolicyService;
    }

    @Override
    public void initializeNote(String actorEmail, UUID noteId) {
        String noteKey = getNoteKey(noteId);

        if (redisTemplate.hasKey(noteKey)) return;

        Note note = notePolicyService.validateEditor(actorEmail, noteId);
        NoteVersion noteVersion = notePolicyService.findNoteCopy(noteId);

        NoteDto redisNote = new NoteDto(
                note.getId(),
                note.getUser().getEmail(),
                note.getTitle(),
                note.getRevisionLog(),
                note.getVisibility(),
                null,
                note.getCurrentNoteVersionNumber(),
                note.getCreatedAt(),
                note.getUpdatedAt()
        );

        NoteVersionDto redisNoteVersion = new NoteVersionDto(
                noteVersion.getId(),
                noteVersion.getMasterDelta(),
                noteVersion.getRevision(),
                noteVersion.getComment(),
                noteVersion.getVersionNumber(),
                noteVersion.getCreatedAt()
        );

        String noteVersionKey = getNoteVersionKey(note.getId());
        String initialRevisionKey = getInitialRevisionKey(noteId);

        try {
            String jsonNote = objectMapper.writeValueAsString(redisNote);
            String jsonNoteVersion = objectMapper.writeValueAsString(redisNoteVersion);

            redisTemplate.opsForValue().set(initialRevisionKey, String.valueOf(noteVersion.getRevision()));
            redisTemplate.opsForValue().set(noteKey, jsonNote);
            redisTemplate.opsForValue().set(noteVersionKey, jsonNoteVersion);
        } catch (JsonProcessingException e) {
            log.error("Failed to initialize note in Redis: {}", noteId, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void updateNote(NoteDto note, NoteVersionDto noteVersion) {
        String noteKey = getNoteKey(note.id());
        String noteVersionKey = getNoteVersionKey(note.id());

        if (redisTemplate.opsForValue().get(noteKey) == null) return;

        try {
            String jsonNote = objectMapper.writeValueAsString(note);
            String jsonNoteVersion = objectMapper.writeValueAsString(noteVersion);
            String noteId = note.id().toString();
            String dirtyTimestamp = String.valueOf(System.currentTimeMillis());

            String script = """
                redis.call('set', KEYS[1], ARGV[1])
                redis.call('set', KEYS[2], ARGV[2])
                redis.call('zadd', KEYS[3], ARGV[3], ARGV[4])
                return 1
                """;

            redisTemplate.execute(
                    new DefaultRedisScript<>(script, Long.class),
                    List.of(noteKey, noteVersionKey, DIRTY_NOTES_KEY),
                    jsonNote,
                    jsonNoteVersion,
                    dirtyTimestamp,
                    noteId
            );
        } catch (Exception e) {
            log.error("Failed to update note atomically: {}", note.id(), e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public NoteDto getNote(UUID noteId) {
        String key = getNoteKey(noteId);
        String jsonNote = redisTemplate.opsForValue().get(key);

        if (jsonNote == null) return null;

        try {
            return objectMapper.readValue(jsonNote, NoteDto.class);
        } catch (Exception e) {
            throw new RuntimeException("Error parsing Note", e);
        }
    }

    @Override
    public void deleteNote(UUID noteId) {
        String noteKey = getNoteKey(noteId);
        String noteVersionKey = getNoteVersionKey(noteId);
        String noteCollaboratorKey = getNoteCollaboratorsKey(noteId);
        String noteCollaboratorSessionsKey = getNoteCollaboratorSessionsKey(noteId);
        String noteInitialRevisionKey = getInitialRevisionKey(noteId);
        String reviewInProgressKey = getReviewInProgressKey(noteId);
        String persistenceLockKey = getPersistenceLockKey(noteId);
        String operationLockKey = getOperationLockKey(noteId);
        String processedOpsKey = getProcessedOperationsKey(noteId);
        String pendingHistoryKey = getPendingHistoryKey(noteId);

        redisTemplate.delete(List.of(
                noteKey,
                noteVersionKey,
                noteCollaboratorKey,
                noteCollaboratorSessionsKey,
                noteInitialRevisionKey,
                reviewInProgressKey,
                persistenceLockKey,
                operationLockKey,
                processedOpsKey,
                pendingHistoryKey
        ));

        redisTemplate.opsForZSet().remove(DIRTY_NOTES_KEY, noteId.toString());
    }

    @Override
    public NoteVersionDto getNoteVersion(UUID noteId) {
        String key = getNoteVersionKey(noteId);
        String jsonNoteVersion = redisTemplate.opsForValue().get(key);

        if (jsonNoteVersion == null) return null;

        try {
            return objectMapper.readValue(jsonNoteVersion, NoteVersionDto.class);
        } catch (Exception e) {
            throw new RuntimeException("Error parsing NoteVersion", e);
        }
    }

    @Override
    public void addCollaboratorToNote(UUID noteId, String actorEmail) {
        String collaboratorsKey = getNoteCollaboratorsKey(noteId);

        Object existingColor = redisTemplate.opsForHash().get(collaboratorsKey, actorEmail);
        if (existingColor != null) return;

        String assignedColor = COLLABORATOR_COLORS[Math.abs(actorEmail.hashCode() % COLLABORATOR_COLORS.length)];

        redisTemplate.opsForHash().put(collaboratorsKey, actorEmail, assignedColor);
    }

    @Override
    public void addCollaboratorSession(UUID noteId, String actorEmail, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("WebSocket sessionId is required");
        }

        addCollaboratorToNote(noteId, actorEmail);

        String sessionsKey = getNoteCollaboratorSessionsKey(noteId);
        redisTemplate.opsForHash().put(sessionsKey, sessionId, actorEmail);

        redisTemplate.opsForSet().add(ACTIVE_COLLABORATION_NOTES_KEY, noteId.toString());
        refreshCollaboratorSessionHeartbeat(noteId, sessionId, actorEmail);

        rebuildCollaboratorsFromSessions(noteId);
    }

    @Override
    public void removeCollaboratorFromNote(UUID noteId, String actorEmail) {
        String collaboratorsKey = getNoteCollaboratorsKey(noteId);
        redisTemplate.opsForHash().delete(collaboratorsKey, actorEmail);
    }

    @Override
    public boolean removeCollaboratorSession(UUID noteId, String sessionId) {
        if (noteId == null || sessionId == null || sessionId.isBlank()) {
            return false;
        }

        String sessionsKey = getNoteCollaboratorSessionsKey(noteId);
        Object actorEmailObj = redisTemplate.opsForHash().get(sessionsKey, sessionId);

        if (actorEmailObj == null) {
            redisTemplate.delete(getCollaboratorSessionHeartbeatKey(noteId, sessionId));
            return false;
        }

        String actorEmail = actorEmailObj.toString();

        redisTemplate.opsForHash().delete(sessionsKey, sessionId);
        redisTemplate.delete(getCollaboratorSessionHeartbeatKey(noteId, sessionId));

        Map<Object, Object> remainingSessions = redisTemplate.opsForHash().entries(sessionsKey);

        boolean stillConnected = remainingSessions
                .values()
                .stream()
                .anyMatch(email -> actorEmail.equals(String.valueOf(email)));

        rebuildCollaboratorsFromSessions(noteId);

        if (remainingSessions.isEmpty()) {
            redisTemplate.opsForSet().remove(
                    ACTIVE_COLLABORATION_NOTES_KEY,
                    noteId.toString()
            );
        }

        return !stillConnected;
    }

    @Override
    public void markNoteDirty(UUID noteId) {
        if (noteId == null) return;

        long now = System.currentTimeMillis();

        redisTemplate.opsForZSet().add(
                DIRTY_NOTES_KEY,
                noteId.toString(),
                now
        );
    }

    @Override
    public Set<UUID> getDirtyNotesDueForPersistence() {
        long cutoff = System.currentTimeMillis() - DIRTY_AGE_MILLIS;

        Set<String> rawNoteIds = redisTemplate.opsForZSet().rangeByScore(
                DIRTY_NOTES_KEY,
                0,
                cutoff,
                0,
                BATCH_SIZE
        );

        if (rawNoteIds == null || rawNoteIds.isEmpty()) {
            return Collections.emptySet();
        }

        Set<UUID> noteIds = new LinkedHashSet<>();

        for (String raw : rawNoteIds) {
            try {
                noteIds.add(UUID.fromString(raw));
            } catch (Exception e) {
                log.warn("Invalid noteId found in dirty set: {}", raw);
                redisTemplate.opsForZSet().remove(DIRTY_NOTES_KEY, raw);
            }
        }

        return noteIds;
    }

    @Override
    public boolean tryAcquirePersistenceLock(UUID noteId, String owner) {
        if (noteId == null || owner == null || owner.isBlank()) {
            return false;
        }

        String key = getPersistenceLockKey(noteId);

        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                key,
                owner,
                Duration.ofSeconds(LOCK_TTL_SECONDS)
        );

        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public void releasePersistenceLock(UUID noteId, String owner) {
        if (noteId == null || owner == null || owner.isBlank()) {
            return;
        }

        String key = getPersistenceLockKey(noteId);

        String script = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """;

        redisTemplate.execute(
                new DefaultRedisScript<>(script, Long.class),
                Collections.singletonList(key),
                owner
        );
    }

    @Override
    public void clearDirtyNoteIfRevisionUnchanged(UUID noteId, int persistedRevision) {
        if (noteId == null) return;

        NoteVersionDto latestVersion = getNoteVersion(noteId);

        if (latestVersion == null) {
            redisTemplate.opsForZSet().remove(DIRTY_NOTES_KEY, noteId.toString());
            return;
        }

        if (latestVersion.revision() == persistedRevision) {
            redisTemplate.opsForZSet().remove(DIRTY_NOTES_KEY, noteId.toString());
            return;
        }

        markNoteDirty(noteId);
    }

    @Override
    public boolean tryAcquireOperationLock(UUID noteId, String owner, long ttlSeconds) {
        if (noteId == null || owner == null || owner.isBlank()) {
            return false;
        }

        String key = getOperationLockKey(noteId);

        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                key,
                owner,
                java.time.Duration.ofSeconds(ttlSeconds)
        );

        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public void releaseOperationLock(UUID noteId, String owner) {
        if (noteId == null || owner == null || owner.isBlank()) {
            return;
        }

        String key = getOperationLockKey(noteId);

        String script = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """;

        redisTemplate.execute(
                new org.springframework.data.redis.core.script.DefaultRedisScript<>(script, Long.class),
                java.util.Collections.singletonList(key),
                owner
        );
    }

    @Override
    public TextOperation getProcessedOperation(UUID noteId, String opId) {
        if (noteId == null || opId == null || opId.isBlank()) {
            return null;
        }

        String key = getProcessedOperationsKey(noteId);
        Object raw = redisTemplate.opsForHash().get(key, opId);

        if (raw == null) {
            return null;
        }

        try {
            return objectMapper.readValue(String.valueOf(raw), TextOperation.class);
        } catch (Exception e) {
            log.error(
                    "Failed to parse processed operation from Redis. noteId={} opId={}",
                    noteId,
                    opId,
                    e
            );

            return null;
        }
    }

    @Override
    public void saveProcessedOperation(UUID noteId, TextOperation operation) {
        if (noteId == null || operation == null) {
            return;
        }

        String opId = operation.getOpId();

        if (opId == null || opId.isBlank()) {
            return;
        }

        try {
            String key = getProcessedOperationsKey(noteId);
            String json = objectMapper.writeValueAsString(operation);

            redisTemplate.opsForHash().put(key, opId, json);
        } catch (Exception e) {
            log.error(
                    "Failed to save processed operation to Redis. noteId={} opId={}",
                    noteId,
                    operation.getOpId(),
                    e
            );

            throw new RuntimeException(e);
        }
    }

    @Override
    public void compactTransformRevisionLogIfNeeded(
            UUID noteId,
            NoteDto note,
            int maxLogSize,
            int keepLatest
    ) {
        if (noteId == null || note == null || note.revisionLog() == null) {
            return;
        }

        if (maxLogSize <= 0 || keepLatest <= 0) {
            return;
        }

        if (keepLatest >= maxLogSize) {
            throw new IllegalArgumentException("keepLatest must be smaller than maxLogSize");
        }

        int currentSize = note.revisionLog().size();

        if (currentSize <= maxLogSize) {
            return;
        }

        int removeCount = currentSize - keepLatest;

        int oldInitialRevision = getInitialRevision(noteId);
        int newInitialRevision = oldInitialRevision + removeCount;

        List<TextOperation> compactedLog =
                new ArrayList<>(note.revisionLog().subList(removeCount, currentSize));

        note.revisionLog().clear();
        note.revisionLog().addAll(compactedLog);

        setInitialRevision(noteId, newInitialRevision);

        log.info(
                "Compacted live transform revision log. noteId={} oldInitialRevision={} newInitialRevision={} removedOps={} remainingOps={}",
                noteId,
                oldInitialRevision,
                newInitialRevision,
                removeCount,
                note.revisionLog().size()
        );
    }

    @Override
    public void appendPendingHistoryOperation(UUID noteId, TextOperation operation) {
        if (noteId == null || operation == null) {
            return;
        }

        try {
            String key = getPendingHistoryKey(noteId);
            String json = objectMapper.writeValueAsString(operation);

            redisTemplate.opsForList().rightPush(key, json);
        } catch (Exception e) {
            log.error(
                    "Failed to append pending history operation. noteId={} opId={}",
                    noteId,
                    operation.getOpId(),
                    e
            );

            throw new RuntimeException(e);
        }
    }

    @Override
    public List<TextOperation> getPendingHistoryOperations(UUID noteId) {
        if (noteId == null) {
            return Collections.emptyList();
        }

        String key = getPendingHistoryKey(noteId);

        List<String> rawItems = redisTemplate.opsForList().range(key, 0, -1);

        if (rawItems == null || rawItems.isEmpty()) {
            return Collections.emptyList();
        }

        List<TextOperation> operations = new ArrayList<>();

        for (String raw : rawItems) {
            try {
                operations.add(objectMapper.readValue(raw, TextOperation.class));
            } catch (Exception e) {
                log.error(
                        "Failed to parse pending history operation. noteId={} raw={}",
                        noteId,
                        raw,
                        e
                );

                throw new RuntimeException(e);
            }
        }

        return operations;
    }

    @Override
    public void clearPendingHistoryOperationsUpToRevision(UUID noteId, int savedRevision) {
        if (noteId == null) {
            return;
        }

        String key = getPendingHistoryKey(noteId);

        List<TextOperation> pending = getPendingHistoryOperations(noteId);

        if (pending.isEmpty()) {
            return;
        }

        List<TextOperation> remaining = pending.stream()
                .filter(op -> op.getRevision() > savedRevision)
                .toList();

        redisTemplate.delete(key);

        for (TextOperation op : remaining) {
            appendPendingHistoryOperation(noteId, op);
        }

        log.info(
                "Cleared pending history operations. noteId={} savedRevision={} removed={} remaining={}",
                noteId,
                savedRevision,
                pending.size() - remaining.size(),
                remaining.size()
        );
    }

    @Override
    public void refreshCollaboratorSessionHeartbeat(UUID noteId, String sessionId, String actorEmail) {
        if (noteId == null || sessionId == null || sessionId.isBlank()) return;

        String key = getCollaboratorSessionHeartbeatKey(noteId, sessionId);

        redisTemplate.opsForValue().set(
                key,
                actorEmail != null ? actorEmail : "",
                java.time.Duration.ofSeconds(COLLABORATOR_SESSION_HEARTBEAT_TTL_SECONDS)
        );
    }

    @Override
    public boolean collaboratorSessionHeartbeatExists(UUID noteId, String sessionId) {
        if (noteId == null || sessionId == null || sessionId.isBlank()) {
            return false;
        }

        return redisTemplate.hasKey(getCollaboratorSessionHeartbeatKey(noteId, sessionId));
    }

    @Override
    public Set<UUID> getActiveCollaborationNoteIds() {
        Set<String> raw = redisTemplate.opsForSet().members(ACTIVE_COLLABORATION_NOTES_KEY);

        if (raw == null || raw.isEmpty()) {
            return java.util.Collections.emptySet();
        }

        Set<UUID> noteIds = new java.util.LinkedHashSet<>();

        for (String item : raw) {
            try {
                noteIds.add(UUID.fromString(item));
            } catch (Exception e) {
                redisTemplate.opsForSet().remove(ACTIVE_COLLABORATION_NOTES_KEY, item);
            }
        }

        return noteIds;
    }

    @Override
    public void cleanupStaleCollaboratorSessions(UUID noteId) {
        if (noteId == null) return;

        String sessionKey = getNoteCollaboratorSessionsKey(noteId);

        Map<Object, Object> sessions = redisTemplate.opsForHash().entries(sessionKey);

        if (sessions.isEmpty()) {
            redisTemplate.opsForSet().remove(ACTIVE_COLLABORATION_NOTES_KEY, noteId.toString());
            return;
        }

        boolean removedAny = false;

        for (Map.Entry<Object, Object> entry : sessions.entrySet()) {
            String sessionId = String.valueOf(entry.getKey());

            if (!collaboratorSessionHeartbeatExists(noteId, sessionId)) {
                redisTemplate.opsForHash().delete(sessionKey, sessionId);
                removedAny = true;
            }
        }

        if (removedAny) {
            rebuildCollaboratorsFromSessions(noteId);
        }

        Map<Object, Object> remainingSessions = redisTemplate.opsForHash().entries(sessionKey);

        if (remainingSessions.isEmpty()) {
            redisTemplate.opsForSet().remove(ACTIVE_COLLABORATION_NOTES_KEY, noteId.toString());
        }
    }

    @Override
    public Map<Object, Object> getCollaborators(UUID noteId) {
        String key = getNoteCollaboratorsKey(noteId);
        return redisTemplate.opsForHash().entries(key);
    }

    @Override
    public void setReviewInProgress(UUID noteId, String ownerEmail, String value) {
        if ("true".equalsIgnoreCase(value)) {
            redisTemplate.opsForHash().put(getReviewInProgressKey(noteId), ownerEmail, "true");
        } else {
            redisTemplate.opsForHash().delete(getReviewInProgressKey(noteId), ownerEmail);
        }
    }

    @Override
    public boolean isReviewInProgress(UUID noteId, String ownerEmail) {
        Object val = redisTemplate.opsForHash().get(getReviewInProgressKey(noteId), ownerEmail);
        return "true".equals(val);
    }

    @Override
    public int getInitialRevision(UUID noteId) {
        String val = redisTemplate.opsForValue().get(getInitialRevisionKey(noteId));
        return val != null ? Integer.parseInt(val) : 0;
    }

    @Override
    public void setInitialRevision(UUID noteId, int revision) {
        if (noteId == null) return;

        redisTemplate.opsForValue().set(
                getInitialRevisionKey(noteId),
                String.valueOf(revision)
        );
    }

    private String getNoteKey(UUID noteId) {
        return "note:" + noteId;
    }

    private String getNoteVersionKey(UUID noteId) {
        return "note-version:" + noteId;
    }

    private String getNoteCollaboratorsKey(UUID noteId) {
        return "note-collaborators:" + noteId;
    }

    private String getInitialRevisionKey(UUID noteId) {
        return "note-initial-revision:" + noteId;
    }

    private String getReviewInProgressKey(UUID noteId) {
        return "note-review-in-progress:" + noteId;
    }

    private String getNoteCollaboratorSessionsKey(UUID noteId) {
        return "note-collaborator-sessions:" + noteId;
    }

    private String getPersistenceLockKey(UUID noteId) {
        return PERSIST_LOCK_PREFIX + noteId;
    }

    private String getOperationLockKey(UUID noteId) {
        return OPERATION_LOCK_PREFIX + noteId;
    }

    private String getProcessedOperationsKey(UUID noteId) {
        return "note-processed-ops:" + noteId;
    }

    private String getPendingHistoryKey(UUID noteId) {
        return "note-pending-history:" + noteId;
    }

    private String getCollaboratorSessionHeartbeatKey(UUID noteId, String sessionId) {
        return "note-session-heartbeat:" + noteId + ":" + sessionId;
    }

    private void rebuildCollaboratorsFromSessions(UUID noteId) {
        String sessionKey = getNoteCollaboratorSessionsKey(noteId);
        String collaboratorsKey = getNoteCollaboratorsKey(noteId);

        Map<Object, Object> sessions = redisTemplate.opsForHash().entries(sessionKey);

        redisTemplate.delete(collaboratorsKey);

        if (sessions.isEmpty()) {
            return;
        }

        Set<String> uniqueEmails = new LinkedHashSet<>();

        for (Object emailObj : sessions.values()) {
            if (emailObj == null) continue;

            String email = String.valueOf(emailObj);

            if (!email.isBlank()) {
                uniqueEmails.add(email);
            }
        }

        for (String email : uniqueEmails) {
            String assignedColor = COLLABORATOR_COLORS[Math.abs(email.hashCode() % COLLABORATOR_COLORS.length)];

            redisTemplate.opsForHash().put(collaboratorsKey, email, assignedColor);
        }
    }
}
