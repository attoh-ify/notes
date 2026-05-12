package com.example.notes.services.impl;

import com.example.notes.dto.note.NoteDto;
import com.example.notes.dto.noteVersion.NoteVersionDto;
import com.example.notes.entities.note.Note;
import com.example.notes.entities.noteVersion.NoteVersion;
import com.example.notes.exceptions.BadRequestException;
import com.example.notes.repositories.NoteRepository;
import com.example.notes.repositories.NoteVersionRepository;
import com.example.notes.services.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

        NoteVersion noteVersion = noteVersionRepository
                .findByNote_IdAndVersionNumber(
                        noteId,
                        note.getCurrentNoteVersionNumber()
                )
                .orElseThrow(() -> {
                    log.warn(
                            "Note version with version number={} not found",
                            note.getCurrentNoteVersionNumber()
                    );
                    return new BadRequestException("Note version not found");
                });

        NoteDto storedNote = redisService.getNote(noteId);
        NoteVersionDto storedNoteVersion = redisService.getNoteVersion(noteId);

        noteVersion.setMasterDelta(storedNoteVersion.masterDelta());
        noteVersion.setRevision(storedNoteVersion.revision());
        noteVersionRepository.save(noteVersion);

        note.setRevisionLog(storedNote.revisionLog());
        noteRepository.save(note);
    }
}