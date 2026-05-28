package com.example.notes.services.impl;

import com.example.notes.dto.note.NoteDto;
import com.example.notes.dto.noteVersion.NoteVersionDto;
import com.example.notes.dto.ot.TextOperation;
import com.example.notes.entities.note.Note;
import com.example.notes.entities.noteVersion.NoteVersion;
import com.example.notes.repositories.NoteRepository;
import com.example.notes.repositories.NoteVersionRepository;
import com.example.notes.services.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class NotePersistenceService {
    private final NotePolicyService notePolicyService;
    private final NoteVersionRepository noteVersionRepository;
    private final NoteRepository noteRepository;
    private final RedisService redisService;

    private static final Logger log =
            LoggerFactory.getLogger(NotePersistenceService.class);

    public NotePersistenceService(
            NotePolicyService notePolicyService,
            NoteVersionRepository noteVersionRepository,
            NoteRepository noteRepository,
            RedisService redisService
    ) {
        this.notePolicyService = notePolicyService;
        this.noteVersionRepository = noteVersionRepository;
        this.noteRepository = noteRepository;
        this.redisService = redisService;
    }

    public void saveRedisNoteToDatabase(String actorEmail, UUID noteId) {
        Note note = notePolicyService.validateEditor(actorEmail, noteId);
        NoteVersion noteVersion = notePolicyService.findNoteCopy(noteId);

        NoteDto storedNote = redisService.getNote(noteId);
        NoteVersionDto storedNoteVersion = redisService.getNoteVersion(noteId);

        if (storedNote == null || storedNoteVersion == null) {
            log.warn(
                    "Skipping Redis note persistence because Redis state is missing. noteId={}",
                    noteId
            );
            return;
        }

        if (storedNoteVersion.revision() >= noteVersion.getRevision()) {
            noteVersion.setMasterDelta(storedNoteVersion.masterDelta());
            noteVersion.setRevision(storedNoteVersion.revision());
            noteVersionRepository.save(noteVersion);
        } else {
            log.warn(
                    "Skipping noteVersion update because Redis revision is older than DB revision. noteId={} redisRevision={} dbRevision={}",
                    noteId,
                    storedNoteVersion.revision(),
                    noteVersion.getRevision()
            );
        }

        if (note.getRevisionLog() == null) {
            note.setRevisionLog(new ArrayList<>());
        }

        List<TextOperation> pendingHistory =
                redisService.getPendingHistoryOperations(noteId);

        if (pendingHistory == null || pendingHistory.isEmpty()) {
            log.info(
                    "Persisted Redis note snapshot. No pending history ops. noteId={} redisRevision={}",
                    noteId,
                    storedNoteVersion.revision()
            );
            return;
        }

        Set<String> existingOpIds = new LinkedHashSet<>();

        for (TextOperation existing : note.getRevisionLog()) {
            if (existing == null) continue;
            if (existing.getOpId() == null || existing.getOpId().isBlank()) continue;

            existingOpIds.add(existing.getOpId());
        }

        int appended = 0;
        int highestSavedRevision = -1;

        for (TextOperation pendingOp : pendingHistory) {
            if (pendingOp == null) continue;
            if (pendingOp.getOpId() == null || pendingOp.getOpId().isBlank()) continue;

            if (existingOpIds.add(pendingOp.getOpId())) {
                note.getRevisionLog().add(pendingOp);
                appended++;
            }

            highestSavedRevision = Math.max(
                    highestSavedRevision,
                    pendingOp.getRevision()
            );
        }

        if (appended > 0) {
            noteRepository.save(note);
        }

        if (highestSavedRevision >= 0) {
            redisService.clearPendingHistoryOperationsUpToRevision(
                    noteId,
                    highestSavedRevision
            );
        }

        log.info(
                "Persisted Redis note to DB. noteId={} redisRevision={} pendingOps={} appendedOps={} highestSavedRevision={}",
                noteId,
                storedNoteVersion.revision(),
                pendingHistory.size(),
                appended,
                highestSavedRevision
        );
    }
}