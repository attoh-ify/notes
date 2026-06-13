package com.crowninteractive.notes.schedulers;

import com.crowninteractive.notes.dto.message_payload.CollaboratorsPayload;
import com.crowninteractive.notes.dto.note.NoteDto;
import com.crowninteractive.notes.notifier.CollaboratorCountNotifier;
import com.crowninteractive.notes.services.RedisService;
import com.crowninteractive.notes.services.impl.NotePersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class CollaboratorSessionCleanupScheduler {
    private final RedisService redisService;
    private final CollaboratorCountNotifier collaboratorCountNotifier;
    private final NotePersistenceService notePersistenceService;

    public CollaboratorSessionCleanupScheduler(
            RedisService redisService,
            CollaboratorCountNotifier collaboratorCountNotifier,
            NotePersistenceService notePersistenceService
    ) {
        this.redisService = redisService;
        this.collaboratorCountNotifier = collaboratorCountNotifier;
        this.notePersistenceService = notePersistenceService;
    }

    @Scheduled(fixedDelay = 600_000)
    public void cleanupStaleSessions() {
        Set<String> activeNoteIds = redisService.getActiveCollaborationNoteIds();

        if (activeNoteIds.isEmpty()) {
            return;
        }

        for (String noteId : activeNoteIds) {
            try {
                cleanupOneNote(noteId);
            } catch (Exception e) {
                log.error("Failed to cleanup collaborator sessions. noteId={}", noteId, e);
            }
        }
    }

    private void cleanupOneNote(String noteId) {
        redisService.cleanupStaleCollaboratorSessions(noteId);

        NoteDto note = redisService.getNote(noteId);
        Map<Object, Object> collaborators = redisService.getCollaborators(noteId);

        if (note == null) {
            return;
        }

        if (collaborators != null && !collaborators.isEmpty()) {
            collaboratorCountNotifier.notifyCount(
                    noteId,
                    new CollaboratorsPayload(collaborators)
            );
            return;
        }

        persistAndDeleteInactiveNote(noteId, note.ownerEmail());
    }

    private void persistAndDeleteInactiveNote(String noteId, String ownerEmail) {
        String lockOwner = UUID.randomUUID().toString();

        boolean locked = redisService.tryAcquirePersistenceLock(
                noteId,
                lockOwner
        );

        if (!locked) {
            log.info(
                    "Skipping inactive note cleanup because persistence lock is busy. noteId={}",
                    noteId
            );
            return;
        }

        try {
            /*
             * Re-check before saving/deleting.
             * A user may have rejoined between cleanupStaleCollaboratorSessions()
             * and acquiring the persistence lock.
             */
            Map<Object, Object> collaboratorsBeforeSave =
                    redisService.getCollaborators(noteId);

            if (collaboratorsBeforeSave != null && !collaboratorsBeforeSave.isEmpty()) {
                log.info(
                        "Skipping inactive note delete because collaborators rejoined. noteId={}",
                        noteId
                );
                return;
            }

            notePersistenceService.saveRedisNoteToDatabase(ownerEmail, noteId);

            /*
             * Re-check again after save.
             * A user may have joined while persistence was running.
             */
            Map<Object, Object> collaboratorsAfterSave =
                    redisService.getCollaborators(noteId);

            if (collaboratorsAfterSave != null && !collaboratorsAfterSave.isEmpty()) {
                log.info(
                        "Skipping Redis delete because collaborators rejoined after save. noteId={}",
                        noteId
                );
                return;
            }

            redisService.deleteNote(noteId);

            log.info(
                    "Persisted and deleted inactive Redis note after stale-session cleanup. noteId={}",
                    noteId
            );
        } finally {
            redisService.releasePersistenceLock(noteId, lockOwner);
        }
    }
}