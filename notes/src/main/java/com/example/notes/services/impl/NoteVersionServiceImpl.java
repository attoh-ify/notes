package com.example.notes.services.impl;

import com.example.notes.dto.message_payload.ReviewInProgressResponsePayload;
import com.example.notes.dto.noteVersion.CreateNoteVersionPayload;
import com.example.notes.dto.noteVersion.NoteVersionDto;
import com.example.notes.dto.ot.OpState;
import com.example.notes.entities.note.Note;
import com.example.notes.entities.noteVersion.NoteVersion;
import com.example.notes.exceptions.BadRequestException;
import com.example.notes.mappers.NoteVersionMapper;
import com.example.notes.notifier.ReviewInProgressNotifier;
import com.example.notes.repositories.NoteRepository;
import com.example.notes.repositories.NoteVersionRepository;
import com.example.notes.services.NoteVersionService;
import com.example.notes.services.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class NoteVersionServiceImpl implements NoteVersionService {
    private final NoteRepository noteRepository;
    private final NoteVersionRepository noteVersionRepository;
    private final NotePolicyService notePolicyService;
    private final NoteVersionMapper noteVersionMapper;
    private final RedisService redisService;

    private static final Logger log =
            LoggerFactory.getLogger(NoteVersionServiceImpl.class);

    @Autowired
    private ReviewInProgressNotifier reviewInProgressNotifier;

    public NoteVersionServiceImpl(NoteRepository noteRepository, NoteVersionRepository noteVersionRepository, NotePolicyService notePolicyService, NoteVersionMapper noteVersionMapper, RedisService redisService) {
        this.noteRepository = noteRepository;
        this.noteVersionRepository = noteVersionRepository;
        this.notePolicyService = notePolicyService;
        this.noteVersionMapper = noteVersionMapper;
        this.redisService = redisService;
    }

    @Transactional(readOnly = true)
    @Override
    public List<NoteVersionDto> fetchAllVersions(String actorEmail, UUID noteId) {
        notePolicyService.validateSuper(actorEmail, noteId);
        return noteVersionRepository.findByNoteIdOrderByVersionNumberAsc(noteId).stream().map(noteVersionMapper::toDto).toList();
    }

    @Transactional(readOnly = true)
    @Override
    public NoteVersionDto fetchVersion(String actorEmail, UUID noteId, int noteVersionNumber) {
        return noteVersionMapper.toDto(noteVersionRepository.findByNote_IdAndVersionNumber(noteId, noteVersionNumber)
                .orElseThrow(() -> {
                    log.warn("Note version with version number = {} not found", noteVersionNumber);
                    return new BadRequestException("Note version not found");
                })
        );
    }

    @Override
    public NoteVersionDto createVersion(String actorEmail, UUID noteId, CreateNoteVersionPayload payload) {
        Note note = notePolicyService.validateSuper(actorEmail, noteId);

        note.getRevisionLog().stream().peek(op -> {
            if (op.getState().equals(OpState.PENDING)) {
                op.setState(OpState.COMMITTED);
            }
        }).toList();

        NoteVersionDto noteVersion = redisService.getNoteVersion(noteId);

        NoteVersion newNoteVersion = new NoteVersion(
                null,
                note,
                noteVersion.masterDelta(),
                noteVersion.revision(),
                payload.comment(),
                noteVersionRepository.findMaxVersionByNoteId(noteId) + 1
        );

        redisService.setReviewInProgress(noteId, actorEmail, "false");
        reviewInProgressNotifier.notifyReviewInProgress(noteId, new ReviewInProgressResponsePayload(noteId, false));

        return noteVersionMapper.toDto(noteVersionRepository.save(newNoteVersion));
    }

    @Transactional
    @Override
    public NoteVersionDto restoreVersion(String actorEmail, UUID noteId, UUID noteVersionId) {
        Note note = notePolicyService.validateSuper(actorEmail, noteId);
        NoteVersion noteVersion = noteVersionRepository.findById(noteVersionId)
                .orElseThrow(() -> {
                    log.warn("Note version not found id={}", noteVersionId);
                    return new BadRequestException(
                            "Note version with this id does not exist."
                    );
                });

        if (!noteVersion.getNote().equals(note)) {
            log.warn("Note and Note version conflicts");
            throw new BadRequestException("Note and Note version conflicts");
        }

        note.setCurrentNoteVersionNumber(noteVersion.getVersionNumber());
        noteRepository.save(note);
        return noteVersionMapper.toDto(noteVersionRepository.save(noteVersion));
    }
}
