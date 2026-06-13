package com.crowninteractive.notes.schedulers;

import com.crowninteractive.notes.dto.note.NoteDto;
import com.crowninteractive.notes.dto.noteVersion.NoteVersionDto;
import com.crowninteractive.notes.services.RedisService;
import com.crowninteractive.notes.services.impl.NotePersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class DirtyNotePersistenceScheduler {
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
        Set<String> dirtyNoteIds =
                redisService.getDirtyNotesDueForPersistence();

        if (dirtyNoteIds.isEmpty()) {
            return;
        }

        for (String noteId : dirtyNoteIds) {
            persistOneDirtyNote(noteId);
        }
    }

    private void persistOneDirtyNote(String noteId) {
        String lockOwner = UUID.randomUUID().toString();

        boolean locked = redisService.tryAcquirePersistenceLock(
                noteId,
                lockOwner
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