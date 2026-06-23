package com.crowninteractive.notes.services.impl;

import com.crowninteractive.notes.config.activeMq.MessageProducer;
import com.crowninteractive.notes.dto.attribution.AttributionBuildResult;
import com.crowninteractive.notes.dto.attribution.AttributionViewMode;
import com.crowninteractive.notes.dto.note.CollaborationMode;
import com.crowninteractive.notes.dto.note.CreateNotePayload;
import com.crowninteractive.notes.dto.note.JoinNoteResponse;
import com.crowninteractive.notes.dto.note.NoteDto;
import com.crowninteractive.notes.dto.enqueue.OperationQueueInPayload;
import com.crowninteractive.notes.dto.message_payload.ReviewInProgressResponsePayload;
import com.crowninteractive.notes.dto.noteVersion.NoteVersionDto;
import com.crowninteractive.notes.dto.ot.Delta;
import com.crowninteractive.notes.dto.ot.OpState;
import com.crowninteractive.notes.dto.ot.TextOperation;
import com.crowninteractive.notes.entities.note.Note;
import com.crowninteractive.notes.entities.note.NoteVisibility;
import com.crowninteractive.notes.entities.noteAccess.NoteAccessRole;
import com.crowninteractive.notes.entities.noteVersion.NoteVersion;
import com.crowninteractive.notes.entities.user.User;
import com.crowninteractive.notes.exceptions.BadRequestException;
import com.crowninteractive.notes.mappers.NoteMapper;
import com.crowninteractive.notes.notifier.ReviewInProgressNotifier;
import com.crowninteractive.notes.repositories.NoteRepository;
import com.crowninteractive.notes.repositories.NoteVersionRepository;
import com.crowninteractive.notes.services.AttributionService;
import com.crowninteractive.notes.services.NoteService;
import com.crowninteractive.notes.services.RedisService;
import com.crowninteractive.notes.utils.Helpers;
import com.crowninteractive.notes.utils.QuillDeltaUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class NoteServiceImpl implements NoteService {
    @Autowired
    private ReviewInProgressNotifier reviewInProgressNotifier;

    private final NoteRepository noteRepository;
    private final NoteMapper noteMapper;
    private final NoteVersionRepository noteVersionRepository;
    private final NotePolicyService notePolicyService;
    private final UserPolicyService userPolicyService;
    private final RedisService redisService;
    private final AttributionService attributionService;
    private final NotePersistenceService notePersistenceService;
    private final MessageProducer messageProducer;

    private static final Logger log = LoggerFactory.getLogger(NoteServiceImpl.class);

    public NoteServiceImpl(NoteRepository noteRepository, NoteMapper noteMapper, NoteVersionRepository noteVersionRepository, NotePolicyService notePolicyService, UserPolicyService userPolicyService, RedisService redisService, AttributionService attributionService, NotePersistenceService notePersistenceService, MessageProducer messageProducer) {
        this.noteRepository = noteRepository;
        this.noteMapper = noteMapper;
        this.noteVersionRepository = noteVersionRepository;
        this.notePolicyService = notePolicyService;
        this.userPolicyService = userPolicyService;
        this.redisService = redisService;
        this.attributionService = attributionService;
        this.notePersistenceService = notePersistenceService;
        this.messageProducer = messageProducer;
    }

    @Transactional(readOnly = true)
    @Override
    public List<NoteDto> fetchNotes(String actorEmail) {
        List<Note> notes = noteRepository.findAccessibleNotes(actorEmail);
        List<NoteDto> noteDtos = new ArrayList<>();
        if (notes.isEmpty()) return Collections.emptyList();
        for (Note note : notes) {
            NoteDto noteDto = noteMapper.toDto(note, actorEmail);
            noteDtos.add(noteDto);
        }
        return noteDtos;
    }

    @Override
    public NoteDto fetchNote(String actorEmail, String noteId) {
        Note note = notePolicyService.findNoteById(noteId);

        boolean hasAccess = notePolicyService.resolveRole(actorEmail, note) != NoteAccessRole.RESTRICTED;
        boolean isPublic = note.getVisibility() == NoteVisibility.PUBLIC;

        if (!hasAccess && !isPublic) {
            return noteMapper.toDtoRestricted();
        }

        return noteMapper.toDto(note, actorEmail);
    }

    @Transactional
    @Override
    public NoteDto createNote(String actorEmail, CreateNotePayload payload) {
        User user = userPolicyService.userExists(actorEmail);

        Note newNote = new Note(
                null,
                UUID.randomUUID().toString(),
                user,
                payload.getTitle(),
                new ArrayList<>(),
                NoteVisibility.PUBLIC,
                new ArrayList<>(),
                0,
                new ArrayList<>(),
                false
        );
        newNote = noteRepository.save(newNote);

        Delta rawInitialDelta =
                payload.getInitialDelta() != null ? payload.getInitialDelta() : new Delta();

        boolean hasContent =
                rawInitialDelta.ops != null && !rawInitialDelta.ops.isEmpty();

        Delta initialDelta =
                hasContent
                        ? QuillDeltaUtils.ensureTerminalNewline(rawInitialDelta)
                        : QuillDeltaUtils.emptyDocument();

        NoteVersion noteCopyVersion  = new NoteVersion(
                null,
                UUID.randomUUID().toString(),
                newNote,
                initialDelta,
                0,
                "Internal Note Copy",
                0
        );
        noteCopyVersion  = noteVersionRepository.save(noteCopyVersion);
        newNote.getNoteVersions().add(noteCopyVersion);

        if (hasContent) {
            NoteVersion importedVersion = new NoteVersion(
                    null,
                    UUID.randomUUID().toString(),
                    newNote,
                    initialDelta,
                    1,
                    "Imported from document",
                    1
            );
            importedVersion = noteVersionRepository.save(importedVersion);
            newNote.getNoteVersions().add(importedVersion);

            TextOperation textOp = new TextOperation(
                    initialDelta,
                    actorEmail,
                    1,
                    OpState.COMMITTED,
                    importedVersion.getCreatedAt()
            );
            List<TextOperation> revisionLog = new ArrayList<>();
            revisionLog.add(textOp);

            newNote.setRevisionLog(revisionLog);
            newNote.setCurrentNoteVersionNumber(1);
        }

        newNote = noteRepository.save(newNote);

        if (newNote.getUser() == null) {
            newNote.setUser(user);
        }

        return noteMapper.toDto(newNote, actorEmail);
    }

    @Override
    public JoinNoteResponse joinNote(String userId, String actorEmail, String noteId) {
        Note persistedNote = notePolicyService.validateEditor(actorEmail, noteId);

        int activeSessionCount = redisService.getActiveSessionCount(noteId);
        CollaborationMode mode = redisService.getCollaborationMode(noteId);

        redisService.initializeNote(actorEmail, noteId);

        NoteDto note = redisService.getNote(noteId);
        NoteVersionDto noteVersion = redisService.getNoteVersion(noteId);

        boolean isReviewing = persistedNote.isReviewing();

        Map<Object, Object> collaborators = redisService.getCollaborators(noteId);

        Delta normalizedMasterDelta =
                QuillDeltaUtils.ensureTerminalNewline(noteVersion.getMasterDelta());

        NoteVersionDto normalizedNoteVersion = new NoteVersionDto(
                noteVersion.getId(),
                noteVersion.getNoteVersionId(),
                normalizedMasterDelta,
                noteVersion.getRevision(),
                noteVersion.getComment(),
                noteVersion.getVersionNumber(),
                noteVersion.getCreatedAt()
        );

        redisService.updateNote(note, normalizedNoteVersion);

        return new JoinNoteResponse(
                collaborators,
                normalizedMasterDelta,
                noteVersion.getRevision(),
                isReviewing,
                mode,
                activeSessionCount
        );
    }

    @Transactional
    @Override
    public void buildAttribution(String actorEmail, String noteId) {
        Note note = notePolicyService.validateOwner(actorEmail, noteId);
        NoteVersion noteVersion = notePolicyService.findNoteCopy(noteId);

        List<TextOperation> committedTextOps = note.getRevisionLog().stream()
                .filter(textOp -> textOp.getState().equals(OpState.COMMITTED))
                .collect(Collectors.toList());

        List<TextOperation> pendingTextOps = note.getRevisionLog().stream()
                .filter(textOp -> textOp.getState().equals(OpState.PENDING))
                .collect(Collectors.toList());

        AttributionBuildResult result = attributionService.buildReviewProjection(
                actorEmail,
                noteId,
                committedTextOps,
                pendingTextOps,
                note.getRevisionLog(),
                AttributionViewMode.REVIEW
        );

        if (result.isRevisionLogChanged()) {
            Delta newMasterDelta =
                    rebuildLiveMasterDeltaFromRevisionLog(note.getRevisionLog());

            noteVersion.setMasterDelta(newMasterDelta);

            noteRepository.save(note);
            noteVersionRepository.save(noteVersion);

            redisService.refreshNoteContent(actorEmail, noteId);
        }
    }

    @Override
    public void startReview(String actorEmail, String noteId) {
        Note note = notePolicyService.validateOwner(actorEmail,noteId);
        note.setReviewing(true);
        noteRepository.save(note);
        redisService.refreshNoteContent(actorEmail, noteId);
        reviewInProgressNotifier.notifyReviewInProgress(noteId, new ReviewInProgressResponsePayload(noteId, true));
    }

    @Override
    public void exitReviewNote(String actorEmail, String noteId) {
        Note note = notePolicyService.validateOwner(actorEmail,noteId);
        note.setReviewing(false);
        noteRepository.save(note);
        redisService.refreshNoteContent(actorEmail, noteId);
        reviewInProgressNotifier.notifyReviewInProgress(noteId, new ReviewInProgressResponsePayload(noteId, false));
    }

    @Override
    public void saveNote(String actorEmail, String noteId) {
        notePersistenceService.saveRedisNoteToDatabase(actorEmail, noteId);
    }

    @Transactional
    @Override
    public void deleteNote(String actorEmail, String noteId) {
        Note note = notePolicyService.validateOwner(actorEmail, noteId);
        noteRepository.delete(note);
        redisService.deleteNote(noteId);
    }

    @Override
    public void changeNoteVisibility(String userEmail, String noteId, NoteVisibility visibility) {
        Note note = notePolicyService.validateSuper(userEmail, noteId);
        note.setVisibility(visibility);
        noteRepository.save(note);
    }

    @Override
    public int soloSyncFromJoinedSession(String actorEmail, String noteId, TextOperation operation) {
        if (operation == null || operation.getDelta() == null) {
            throw new BadRequestException("Solo sync operation is required");
        }

        if (operation.getOpId() == null || Helpers.isBlank(operation.getOpId())) {
            throw new BadRequestException("Solo sync opId is required");
        }

        redisService.initializeNote(actorEmail, noteId);

        NoteDto redisNote = redisService.getNote(noteId);
        boolean isOwner = redisNote.getOwnerEmail().equals(actorEmail);

        if (redisNote.getIsReviewing() && !isOwner) {
            throw new BadRequestException("Note is currently under review by the owner.");
        }

        String lockOwner = UUID.randomUUID().toString();

        boolean acquiredLock = redisService.tryAcquireOperationLock(noteId, lockOwner, 60);

        if (!acquiredLock) {
            throw new BadRequestException("Could not acquire note operation lock");
        }

        try {
            if (redisService.isCollaborativeMode(noteId)) {
                OperationQueueInPayload payload = new OperationQueueInPayload(
                        noteId,
                        operation.getOpId(),
                        operation.getRevision(),
                        actorEmail,
                        operation.getDelta()
                );

                messageProducer.sendMessage(payload, noteId);

                return 0;
            }

            NoteDto note = redisService.getNote(noteId);
            NoteVersionDto noteVersion = redisService.getNoteVersion(noteId);

            if (note == null || noteVersion == null) {
                throw new BadRequestException("Note is not initialized");
            }

            String opId = operation.getOpId();

            TextOperation alreadyProcessed = redisService.getProcessedOperation(noteId, opId);

            if (alreadyProcessed != null) {
                return alreadyProcessed.getRevision();
            }

            int serverRevision = noteVersion.getRevision();
            int clientRevision = operation.getRevision();

            if (clientRevision != serverRevision) {
                throw new BadRequestException("Solo sync is stale. Please reload note.");
            }

            Delta currentMasterDelta = QuillDeltaUtils.ensureTerminalNewline(noteVersion.getMasterDelta());

            Delta changeDelta = operation.getDelta();

            Delta newMasterDelta =
                    QuillDeltaUtils.ensureTerminalNewline(
                            currentMasterDelta.compose(changeDelta)
                    );

            int newRevision = serverRevision + 1;

            TextOperation newTextOperation = new TextOperation(
                    opId,
                    changeDelta,
                    actorEmail,
                    newRevision,
                    OpState.PENDING,
                    java.time.LocalDateTime.now()
            );

            note.getRevisionLog().add(newTextOperation);

            NoteDto updatedNote = new NoteDto(
                    note.getId(),
                    note.getNoteId(),
                    note.getOwnerEmail(),
                    note.getTitle(),
                    note.getRevisionLog(),
                    note.getVisibility(),
                    note.getAccessRole(),
                    note.getCurrentNoteVersionNumber(),
                    note.getIsReviewing(),
                    note.getCreatedAt(),
                    note.getUpdatedAt()
            );

            NoteVersionDto updatedVersion = new NoteVersionDto(
                    noteVersion.getId(),
                    noteVersion.getNoteVersionId(),
                    newMasterDelta,
                    newRevision,
                    noteVersion.getComment(),
                    noteVersion.getVersionNumber(),
                    noteVersion.getCreatedAt()
            );

            redisService.updateNote(updatedNote, updatedVersion);
            redisService.appendPendingHistoryOperation(noteId, newTextOperation);
            redisService.saveProcessedOperation(noteId, newTextOperation);

            redisService.compactTransformRevisionLogIfNeeded(
                    noteId,
                    updatedNote
            );

            return newRevision;
        } finally {
            redisService.releaseOperationLock(noteId, lockOwner);
        }
    }

    private Delta rebuildLiveMasterDeltaFromRevisionLog(List<TextOperation> revisionLog) {
        Delta delta = QuillDeltaUtils.emptyDocument();

        List<TextOperation> liveOps = revisionLog.stream()
                .filter(this::shouldIncludeInLiveMaster)
                .sorted(Comparator.comparingInt(TextOperation::getRevision))
                .collect(Collectors.toList());

        for (TextOperation op : liveOps) {
            delta = delta.compose(new Delta(op.getDelta().ops));
        }

        return QuillDeltaUtils.ensureTerminalNewline(delta);
    }

    private boolean shouldIncludeInLiveMaster(TextOperation op) {
        return op.getState().equals(OpState.COMMITTED)
                || op.getState().equals(OpState.PENDING);
    }
}