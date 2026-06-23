package com.crowninteractive.notes.services.impl;

import com.crowninteractive.notes.dto.attribution.AttributionViewMode;
import com.crowninteractive.notes.dto.attribution.AuditProjection;
import com.crowninteractive.notes.dto.noteVersion.CreateNoteVersionPayload;
import com.crowninteractive.notes.dto.noteVersion.NoteVersionDto;
import com.crowninteractive.notes.dto.ot.Delta;
import com.crowninteractive.notes.dto.ot.OpState;
import com.crowninteractive.notes.dto.ot.TextOperation;
import com.crowninteractive.notes.entities.note.Note;
import com.crowninteractive.notes.entities.noteVersion.NoteVersion;
import com.crowninteractive.notes.exceptions.BadRequestException;
import com.crowninteractive.notes.mappers.NoteVersionMapper;
import com.crowninteractive.notes.repositories.NoteRepository;
import com.crowninteractive.notes.repositories.NoteVersionRepository;
import com.crowninteractive.notes.services.AttributionService;
import com.crowninteractive.notes.services.NoteService;
import com.crowninteractive.notes.services.NoteVersionService;
import com.crowninteractive.notes.services.RedisService;
import com.crowninteractive.notes.utils.QuillDeltaUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class NoteVersionServiceImpl implements NoteVersionService {
    private final NoteRepository noteRepository;
    private final NoteVersionRepository noteVersionRepository;
    private final NotePolicyService notePolicyService;
    private final NoteVersionMapper noteVersionMapper;
    private final RedisService redisService;
    private final AttributionService attributionService;
    private final NoteService noteService;

    private static final Logger log =
            LoggerFactory.getLogger(NoteVersionServiceImpl.class);

    public NoteVersionServiceImpl(NoteRepository noteRepository, NoteVersionRepository noteVersionRepository, NotePolicyService notePolicyService, NoteVersionMapper noteVersionMapper, RedisService redisService, AttributionService attributionService, NoteService noteService) {
        this.noteRepository = noteRepository;
        this.noteVersionRepository = noteVersionRepository;
        this.notePolicyService = notePolicyService;
        this.noteVersionMapper = noteVersionMapper;
        this.redisService = redisService;
        this.attributionService = attributionService;
        this.noteService = noteService;
    }

    @Transactional(readOnly = true)
    @Override
    public List<NoteVersionDto> fetchAllVersions(String actorEmail, String noteId) {
        notePolicyService.validateSuper(actorEmail, noteId);
        return noteVersionRepository.findByNote_NoteIdOrderByVersionNumberAsc(noteId).stream().map(noteVersionMapper::toDto).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    @Override
    public NoteVersionDto fetchVersion(String actorEmail, String noteId, int noteVersionNumber) {
        return noteVersionMapper.toDto(noteVersionRepository.findByNote_NoteIdAndVersionNumber(noteId, noteVersionNumber)
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
            String noteId,
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
                .collect(Collectors.toList());

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
                noteVersionRepository.findMaxVersionByNote_NoteId(noteId) + 1;

        NoteVersion newNoteVersion = new NoteVersion(
                null,
                UUID.randomUUID().toString(),
                note,
                newMasterDelta,
                newRevision,
                payload.getComment(),
                nextVersionNumber
        );

        note.setCurrentNoteVersionNumber(nextVersionNumber);
        note.setReviewing(false);

        noteRepository.save(note);

        NoteVersion savedVersion = noteVersionRepository.save(newNoteVersion);

//        noteService.buildAttribution(actorEmail, noteId);
        redisService.refreshNoteContent(actorEmail, noteId);

        return noteVersionMapper.toDto(savedVersion);
    }

    @Transactional
    @Override
    public NoteVersionDto restoreVersion(String actorEmail, String noteId, String noteVersionId) {
        Note note = notePolicyService.validateSuper(actorEmail, noteId);
        NoteVersion noteVersion = noteVersionRepository.findByNoteVersionId(noteVersionId)
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

        NoteVersion noteCopy = notePolicyService.findNoteCopy(noteId);
        noteCopy.setMasterDelta(noteVersion.getMasterDelta());
        // need to update the revision

        note.setCurrentNoteVersionNumber(noteVersion.getVersionNumber());
        noteRepository.save(note);
        return noteVersionMapper.toDto(noteVersionRepository.save(noteVersion));
    }

    @Transactional(readOnly = true)
    @Override
    public AuditProjection auditVersion(String actorEmail, String noteId, String versionId) {
        Note note = notePolicyService.validateSuper(actorEmail, noteId);

        NoteVersion targetVersion = noteVersionRepository.findByNote_NoteIdAndNoteVersionId(noteId, versionId)
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
                    .collect(Collectors.toList());

            return attributionService.buildReviewProjection(
                    actorEmail,
                    noteId,
                    new ArrayList<>(),
                    changeTextOps,
                    new ArrayList<>(),
                    AttributionViewMode.AUDIT
            ).getProjection();
        }

        int baseVersionNumber = targetVersion.getVersionNumber() - 1;

        NoteVersion baseVersion = noteVersionRepository
                .findByNote_NoteIdAndVersionNumber(noteId, baseVersionNumber)
                .orElseThrow(() -> {
                    log.warn("Previous note version with version number={} not found", baseVersionNumber);
                    return new BadRequestException("Previous note version not found");
                });

        int baseRevision = baseVersion.getRevision();

        List<TextOperation> baseTextOps = note.getRevisionLog().stream()
                .filter(op -> !op.getState().equals(OpState.DEAD))
                .filter(op -> op.getRevision() <= baseRevision)
                .sorted(Comparator.comparingInt(TextOperation::getRevision))
                .collect(Collectors.toList());

        List<TextOperation> changeTextOps = note.getRevisionLog().stream()
                .filter(op -> !op.getState().equals(OpState.DEAD))
                .filter(op -> op.getRevision() > baseRevision)
                .filter(op -> op.getRevision() <= targetRevision)
                .sorted(Comparator.comparingInt(TextOperation::getRevision))
                .collect(Collectors.toList());

        return attributionService.buildReviewProjection(
                actorEmail,
                noteId,
                baseTextOps,
                changeTextOps,
                new ArrayList<>(),
                AttributionViewMode.AUDIT
        ).getProjection();
    }
}
