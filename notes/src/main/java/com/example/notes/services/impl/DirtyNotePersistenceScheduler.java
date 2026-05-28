package com.example.notes.services.impl;

import com.example.notes.dto.note.NoteDto;
import com.example.notes.dto.noteVersion.NoteVersionDto;
import com.example.notes.services.RedisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class DirtyNotePersistenceScheduler {
    private static final int BATCH_SIZE = 1000;
    private static final long DIRTY_AGE_MILLIS = 60_000;
    private static final long LOCK_TTL_SECONDS = 120;

    private final RedisService redisService;
    private final NotePersistenceService notePersistenceService;

    public DirtyNotePersistenceScheduler(
            RedisService redisService,
            NotePersistenceService notePersistenceService
    ) {
        this.redisService = redisService;
        this.notePersistenceService = notePersistenceService;
    }

    @Scheduled(fixedDelay = 60_000)
    public void persistDirtyNotes() {
        Set<UUID> dirtyNoteIds =
                redisService.getDirtyNotesDueForPersistence(
                        BATCH_SIZE,
                        DIRTY_AGE_MILLIS
                );

        if (dirtyNoteIds.isEmpty()) {
            return;
        }

        for (UUID noteId : dirtyNoteIds) {
            persistOneDirtyNote(noteId);
        }
    }

    private void persistOneDirtyNote(UUID noteId) {
        String lockOwner = UUID.randomUUID().toString();

        boolean locked = redisService.tryAcquirePersistenceLock(
                noteId,
                lockOwner,
                LOCK_TTL_SECONDS
        );

        if (!locked) {
            return;
        }

        try {
            NoteDto note = redisService.getNote(noteId);
            NoteVersionDto noteVersion = redisService.getNoteVersion(noteId);

            if (note == null || noteVersion == null) {
                redisService.clearDirtyNoteIfRevisionUnchanged(noteId, -1);
                return;
            }

            int revisionBeforeSave = noteVersion.revision();

            log.info(
                    "Persisting dirty note. noteId={} revision={}",
                    noteId,
                    revisionBeforeSave
            );

            notePersistenceService.saveRedisNoteToDatabase(
                    note.ownerEmail(),
                    noteId
            );

            redisService.clearDirtyNoteIfRevisionUnchanged(
                    noteId,
                    revisionBeforeSave
            );

            log.info(
                    "Dirty note persistence completed. noteId={} revision={}",
                    noteId,
                    revisionBeforeSave
            );
        } catch (Exception e) {
            log.error("Failed to persist dirty note. noteId={}", noteId, e);
            redisService.markNoteDirty(noteId);
        } finally {
            redisService.releasePersistenceLock(noteId, lockOwner);
        }
    }
}