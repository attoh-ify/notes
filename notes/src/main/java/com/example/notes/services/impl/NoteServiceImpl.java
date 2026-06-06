package com.example.notes.services.impl;

import com.example.notes.config.activeMq.MessageProducer;
import com.example.notes.dto.attribution.*;
import com.example.notes.dto.enqueue.OperationQueueInPayload;
import com.example.notes.dto.message_payload.ReviewInProgressResponsePayload;
import com.example.notes.dto.note.*;
import com.example.notes.dto.noteVersion.NoteVersionDto;
import com.example.notes.dto.ot.Delta;
import com.example.notes.dto.ot.OpState;
import com.example.notes.dto.ot.TextOperation;
import com.example.notes.entities.note.Note;
import com.example.notes.entities.note.NoteVisibility;
import com.example.notes.entities.noteAccess.NoteAccessRole;
import com.example.notes.entities.noteVersion.NoteVersion;
import com.example.notes.entities.user.User;
import com.example.notes.exceptions.BadRequestException;
import com.example.notes.mappers.NoteMapper;
import com.example.notes.notifier.ReviewInProgressNotifier;
import com.example.notes.repositories.NoteRepository;
import com.example.notes.repositories.NoteVersionRepository;
import com.example.notes.services.AttributionService;
import com.example.notes.services.NoteService;
import com.example.notes.services.RedisService;
import com.example.notes.utils.QuillDeltaUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

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
        List<Note> notes = noteRepository.findByActorEmail(actorEmail);
        List<NoteDto> noteDtos = new ArrayList<>();
        if (notes.isEmpty()) return List.of();
        for (Note note : notes) {
            NoteDto noteDto = noteMapper.toDto(note, actorEmail);
            noteDtos.add(noteDto);
        }
        return noteDtos;
    }

    @Override
    public NoteDto fetchNote(String actorEmail, UUID noteId) {
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
                user,
                payload.title(),
                new ArrayList<>(),
                NoteVisibility.PUBLIC,
                new ArrayList<>(),
                0,
                new ArrayList<>()
        );
        newNote = noteRepository.save(newNote);

        Delta rawInitialDelta =
                payload.initialDelta() != null ? payload.initialDelta() : new Delta();

        boolean hasContent =
                rawInitialDelta.ops != null && !rawInitialDelta.ops.isEmpty();

        Delta initialDelta =
                hasContent
                        ? QuillDeltaUtils.ensureTerminalNewline(rawInitialDelta)
                        : QuillDeltaUtils.emptyDocument();

        NoteVersion noteCopyVersion  = new NoteVersion(
                null,
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
    public JoinNoteResponse joinNote(UUID userId, String actorEmail, UUID noteId) {
        notePolicyService.validateEditor(actorEmail, noteId);

        int activeSessionCount = redisService.getActiveSessionCount(noteId);
        CollaborationMode mode = redisService.getCollaborationMode(noteId);

        redisService.initializeNote(actorEmail, noteId);

        NoteDto note = redisService.getNote(noteId);
        NoteVersionDto noteVersion = redisService.getNoteVersion(noteId);

        boolean isReviewing = redisService.isReviewInProgress(
                noteId,
                note.ownerEmail()
        );

        Map<Object, Object> collaborators = redisService.getCollaborators(noteId);

        Delta normalizedMasterDelta =
                QuillDeltaUtils.ensureTerminalNewline(noteVersion.masterDelta());

        NoteVersionDto normalizedNoteVersion = new NoteVersionDto(
                noteVersion.id(),
                normalizedMasterDelta,
                noteVersion.revision(),
                noteVersion.comment(),
                noteVersion.versionNumber(),
                noteVersion.createdAt()
        );

        redisService.updateNote(note, normalizedNoteVersion);

        return new JoinNoteResponse(
                collaborators,
                normalizedMasterDelta,
                noteVersion.revision(),
                isReviewing,
                mode,
                activeSessionCount
        );
    }

    @Transactional
    @Override
    public ReviewProjection buildAttribution(String actorEmail, UUID noteId) {
        Note note = notePolicyService.validateOwner(actorEmail, noteId);
        NoteVersion noteVersion = notePolicyService.findNoteCopy(noteId);

        List<TextOperation> committedTextOps = note.getRevisionLog().stream()
                .filter(textOp -> textOp.getState().equals(OpState.COMMITTED))
                .toList();

        List<TextOperation> pendingTextOps = note.getRevisionLog().stream()
                .filter(textOp -> textOp.getState().equals(OpState.PENDING))
                .toList();

        AttributionBuildResult result = attributionService.buildReviewProjection(
                actorEmail,
                noteId,
                committedTextOps,
                pendingTextOps,
                note.getRevisionLog(),
                AttributionViewMode.REVIEW
        );

        if (result.revisionLogChanged()) {
            Delta newMasterDelta =
                    rebuildLiveMasterDeltaFromRevisionLog(note.getRevisionLog());

            noteVersion.setMasterDelta(newMasterDelta);

            noteRepository.save(note);
            noteVersionRepository.save(noteVersion);

            redisService.refreshNoteContent(actorEmail, noteId);
        }

        return result.projection();
    }

    @Override
    public void startReview(String actorEmail, UUID noteId) {
        notePolicyService.validateOwner(actorEmail,noteId);
        redisService.setReviewInProgress(noteId, actorEmail, "true");
        reviewInProgressNotifier.notifyReviewInProgress(noteId, new ReviewInProgressResponsePayload(noteId, true));
    }

    @Transactional
    @Override
    public void applyReviewChanges(String actorEmail, UUID noteId, ReviewNotePayload payload) {
        Note note = notePolicyService.validateOwner(actorEmail, noteId);
        NoteVersion noteVersion = notePolicyService.findNoteCopy(noteId);

        ReviewOperationAccumulator accumulator = new ReviewOperationAccumulator();

        if (payload.acceptedReferences() != null) {
            for (ReviewDecisionReference ref : payload.acceptedReferences()) {
                accumulator.recordAcceptedReference(ref);
            }
        }

        if (payload.rejectedReferences() != null) {
            for (ReviewDecisionReference ref : payload.rejectedReferences()) {
                accumulator.recordRejectedReference(ref);
            }
        }

        ReviewOperationAccumulator.ReviewApplyResult result =
                accumulator.applyReviewDecisionsToRevisionLog(note.getRevisionLog());

        if (!result.changed()) return;

        Delta newMasterDelta =
                rebuildLiveMasterDeltaFromRevisionLog(note.getRevisionLog());
        noteVersion.setMasterDelta(newMasterDelta);

        noteVersionRepository.save(noteVersion);

        redisService.refreshNoteContent(actorEmail, noteId);
    }

    @Override
    public void exitReviewNote(String actorEmail, UUID noteId) {
        notePolicyService.validateOwner(actorEmail,noteId);
        redisService.setReviewInProgress(noteId, actorEmail, "false");
        reviewInProgressNotifier.notifyReviewInProgress(noteId, new ReviewInProgressResponsePayload(noteId, false));
    }

    @Override
    public void saveNote(String actorEmail, UUID noteId) {
        notePersistenceService.saveRedisNoteToDatabase(actorEmail, noteId);
    }

    @Transactional
    @Override
    public void deleteNote(String actorEmail, UUID noteId) {
        Note note = notePolicyService.validateOwner(actorEmail, noteId);
        noteRepository.delete(note);
        redisService.deleteNote(noteId);
    }

    @Override
    public void changeNoteVisibility(String userEmail, UUID noteId, NoteVisibility visibility) {
        Note note = notePolicyService.validateSuper(userEmail, noteId);
        note.setVisibility(visibility);
        noteRepository.save(note);
    }

    @Override
    public int soloSyncFromJoinedSession(String actorEmail, UUID noteId, TextOperation operation) {
        if (operation == null || operation.getDelta() == null) {
            throw new BadRequestException("Solo sync operation is required");
        }

        if (operation.getOpId() == null || operation.getOpId().isBlank()) {
            throw new BadRequestException("Solo sync opId is required");
        }

        redisService.initializeNote(actorEmail, noteId);

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

            int serverRevision = noteVersion.revision();
            int clientRevision = operation.getRevision();

            if (clientRevision != serverRevision) {
                throw new BadRequestException("Solo sync is stale. Please reload note.");
            }

            Delta currentMasterDelta = QuillDeltaUtils.ensureTerminalNewline(noteVersion.masterDelta());

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

            note.revisionLog().add(newTextOperation);

            NoteDto updatedNote = new NoteDto(
                    note.id(),
                    note.ownerEmail(),
                    note.title(),
                    note.revisionLog(),
                    note.visibility(),
                    note.accessRole(),
                    note.currentNoteVersionNumber(),
                    note.createdAt(),
                    note.updatedAt()
            );

            NoteVersionDto updatedVersion = new NoteVersionDto(
                    noteVersion.id(),
                    newMasterDelta,
                    newRevision,
                    noteVersion.comment(),
                    noteVersion.versionNumber(),
                    noteVersion.createdAt()
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
                .toList();

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