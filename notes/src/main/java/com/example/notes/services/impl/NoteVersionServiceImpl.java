package com.example.notes.services.impl;

import com.example.notes.dto.attribution.AttributionViewMode;
import com.example.notes.dto.attribution.ReviewProjection;
import com.example.notes.dto.noteVersion.CreateNoteVersionPayload;
import com.example.notes.dto.noteVersion.NoteVersionDto;
import com.example.notes.dto.ot.Delta;
import com.example.notes.dto.ot.OpState;
import com.example.notes.dto.ot.TextOperation;
import com.example.notes.entities.note.Note;
import com.example.notes.entities.noteVersion.NoteVersion;
import com.example.notes.exceptions.BadRequestException;
import com.example.notes.mappers.NoteVersionMapper;
import com.example.notes.repositories.NoteRepository;
import com.example.notes.repositories.NoteVersionRepository;
import com.example.notes.services.AttributionService;
import com.example.notes.services.NoteVersionService;
import com.example.notes.services.RedisService;
import com.example.notes.utils.QuillDeltaUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class NoteVersionServiceImpl implements NoteVersionService {
    private final NoteRepository noteRepository;
    private final NoteVersionRepository noteVersionRepository;
    private final NotePolicyService notePolicyService;
    private final NoteVersionMapper noteVersionMapper;
    private final RedisService redisService;
    private final AttributionService attributionService;

    private static final Logger log =
            LoggerFactory.getLogger(NoteVersionServiceImpl.class);

    public NoteVersionServiceImpl(NoteRepository noteRepository, NoteVersionRepository noteVersionRepository, NotePolicyService notePolicyService, NoteVersionMapper noteVersionMapper, RedisService redisService, AttributionService attributionService) {
        this.noteRepository = noteRepository;
        this.noteVersionRepository = noteVersionRepository;
        this.notePolicyService = notePolicyService;
        this.noteVersionMapper = noteVersionMapper;
        this.redisService = redisService;
        this.attributionService = attributionService;
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

    @Transactional
    @Override
    public NoteVersionDto createVersion(
            String actorEmail,
            UUID noteId,
            CreateNoteVersionPayload payload
    ) {
        Note note = notePolicyService.validateSuper(actorEmail, noteId);
        NoteVersion oldNoteVersion = notePolicyService.findNoteCopy(noteId);

        for (TextOperation op : note.getRevisionLog()) {
            if (op.getState().equals(OpState.PENDING)) {
                op.setState(OpState.COMMITTED);
            }
        }

        Delta newMasterDelta = QuillDeltaUtils.emptyDocument();

        List<TextOperation> committedOps = note.getRevisionLog().stream()
                .filter(op -> op.getState().equals(OpState.COMMITTED))
                .sorted(Comparator.comparingInt(TextOperation::getRevision))
                .toList();

        for (TextOperation textOp : committedOps) {
            newMasterDelta = newMasterDelta.compose(
                    new Delta(textOp.getDelta().ops)
            );
        }

        newMasterDelta = QuillDeltaUtils.ensureTerminalNewline(newMasterDelta);

        int newRevision = committedOps.stream()
                .mapToInt(TextOperation::getRevision)
                .max()
                .orElse(oldNoteVersion.getRevision());

        int nextVersionNumber =
                noteVersionRepository.findMaxVersionByNoteId(noteId) + 1;

        NoteVersion newNoteVersion = new NoteVersion(
                null,
                note,
                newMasterDelta,
                newRevision,
                payload.comment(),
                nextVersionNumber
        );

        note.setCurrentNoteVersionNumber(nextVersionNumber);

        noteRepository.save(note);

        NoteVersion savedVersion = noteVersionRepository.save(newNoteVersion);

        redisService.setReviewInProgress(noteId, actorEmail, "false");
        redisService.refreshNoteContent(actorEmail, noteId);

        return noteVersionMapper.toDto(savedVersion);
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

    @Transactional(readOnly = true)
    @Override
    public ReviewProjection auditVersion(String actorEmail, UUID noteId, UUID versionId) {
        Note note = notePolicyService.validateSuper(actorEmail, noteId);

        NoteVersion targetVersion = noteVersionRepository.findByNote_IdAndId(noteId, versionId)
                .orElseThrow(() -> {
                    log.warn("Note version with id={} not found", versionId);
                    return new BadRequestException("Note version not found");
                });

        if (targetVersion.getVersionNumber() <= 0) {
            throw new BadRequestException(
                    "Cannot audit working copy(version 0) as a saved version"
            );
        }

        int targetRevision = targetVersion.getRevision();

        if (targetVersion.getVersionNumber() == 1) {
            List<TextOperation> changeTextOps = note.getRevisionLog().stream()
                    .filter(op -> !op.getState().equals(OpState.DEAD))
                    .filter(op -> op.getRevision() <= targetRevision)
                    .sorted(Comparator.comparingInt(TextOperation::getRevision))
                    .toList();

            return attributionService.buildReviewProjection(
                    actorEmail,
                    noteId,
                    new ArrayList<>(),
                    changeTextOps,
                    AttributionViewMode.AUDIT
            ).projection();
        }

        int baseVersionNumber = targetVersion.getVersionNumber() - 1;

        NoteVersion baseVersion = noteVersionRepository
                .findByNote_IdAndVersionNumber(noteId, baseVersionNumber)
                .orElseThrow(() -> {
                    log.warn("Previous note version with version number={} not found", baseVersionNumber);
                    return new BadRequestException("Previous note version not found");
                });

        int baseRevision = baseVersion.getRevision();

        List<TextOperation> baseTextOps = note.getRevisionLog().stream()
                .filter(op -> !op.getState().equals(OpState.DEAD))
                .filter(op -> op.getRevision() <= baseRevision)
                .sorted(Comparator.comparingInt(TextOperation::getRevision))
                .toList();

        List<TextOperation> changeTextOps = note.getRevisionLog().stream()
                .filter(op -> !op.getState().equals(OpState.DEAD))
                .filter(op -> op.getRevision() > baseRevision)
                .filter(op -> op.getRevision() <= targetRevision)
                .sorted(Comparator.comparingInt(TextOperation::getRevision))
                .toList();

        return attributionService.buildReviewProjection(
                actorEmail,
                noteId,
                baseTextOps,
                changeTextOps,
                AttributionViewMode.AUDIT
        ).projection();
    }
}
