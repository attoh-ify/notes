package com.crowninteractive.notes.services.impl;

import com.crowninteractive.notes.dto.note.NoteDto;
import com.crowninteractive.notes.dto.noteVersion.NoteVersionDto;
import com.crowninteractive.notes.dto.ot.TextOperation;
import com.crowninteractive.notes.entities.note.Note;
import com.crowninteractive.notes.entities.noteVersion.NoteVersion;
import com.crowninteractive.notes.repositories.NoteRepository;
import com.crowninteractive.notes.repositories.NoteVersionRepository;
import com.crowninteractive.notes.services.RedisService;
import com.crowninteractive.notes.utils.Helpers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    public void saveRedisNoteToDatabase(String actorEmail, String noteId) {
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

        if (storedNoteVersion.getRevision() >= noteVersion.getRevision()) {
            noteVersion.setMasterDelta(storedNoteVersion.getMasterDelta());
            noteVersion.setRevision(storedNoteVersion.getRevision());
            noteVersionRepository.save(noteVersion);
        } else {
            log.warn(
                    "Skipping noteVersion update because Redis revision is older than DB revision. noteId={} redisRevision={} dbRevision={}",
                    noteId,
                    storedNoteVersion.getRevision(),
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
                    storedNoteVersion.getRevision()
            );
            return;
        }

        Set<String> existingOpIds = new LinkedHashSet<>();

        for (TextOperation existing : note.getRevisionLog()) {
            if (existing == null) continue;
            if (existing.getOpId() == null || Helpers.isBlank(existing.getOpId())) continue;

            existingOpIds.add(existing.getOpId());
        }

        int appended = 0;
        int highestSavedRevision = -1;

        for (TextOperation pendingOp : pendingHistory) {
            if (pendingOp == null) continue;
            if (pendingOp.getOpId() == null || Helpers.isBlank(pendingOp.getOpId())) continue;

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
                storedNoteVersion.getRevision(),
                pendingHistory.size(),
                appended,
                highestSavedRevision
        );
    }
}