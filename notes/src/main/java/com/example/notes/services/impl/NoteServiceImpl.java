package com.example.notes.services.impl;

import com.example.notes.dto.attribution.ReviewDecisionReference;
import com.example.notes.dto.attribution.ReviewOperationAccumulator;
import com.example.notes.dto.attribution.ReviewProjection;
import com.example.notes.dto.attribution.Reference;
import com.example.notes.dto.message_payload.CollaboratorsPayload;
import com.example.notes.dto.message_payload.CursorPayload;
import com.example.notes.dto.message_payload.ReviewInProgressResponsePayload;
import com.example.notes.dto.note.*;
import com.example.notes.dto.noteVersion.NoteVersionDto;
import com.example.notes.dto.ot.Delta;
import com.example.notes.dto.ot.Op;
import com.example.notes.dto.ot.OpState;
import com.example.notes.dto.ot.TextOperation;
import com.example.notes.entities.note.Note;
import com.example.notes.entities.note.NoteVisibility;
import com.example.notes.entities.noteAccess.NoteAccessRole;
import com.example.notes.entities.noteVersion.NoteVersion;
import com.example.notes.entities.user.User;
import com.example.notes.exceptions.BadRequestException;
import com.example.notes.mappers.NoteMapper;
import com.example.notes.notifier.CollaboratorCountNotifier;
import com.example.notes.notifier.CursorNotifier;
import com.example.notes.notifier.ReviewInProgressNotifier;
import com.example.notes.repositories.NoteRepository;
import com.example.notes.repositories.NoteVersionRepository;
import com.example.notes.services.AttributionService;
import com.example.notes.services.NoteService;
import com.example.notes.services.RedisService;
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
    private CollaboratorCountNotifier collaboratorCountNotifier;

    @Autowired
    private ReviewInProgressNotifier reviewInProgressNotifier;

    @Autowired
    private CursorNotifier cursorNotifier;

    private final NoteRepository noteRepository;
    private final NoteMapper noteMapper;
    private final NoteVersionRepository noteVersionRepository;
    private final NotePolicyService notePolicyService;
    private final UserPolicyService userPolicyService;
    private final RedisService redisService;
    private final AttributionService attributionService;
    private final NotePersistenceService notePersistenceService;

    private static final Logger log =
            LoggerFactory.getLogger(NoteServiceImpl.class);

    public NoteServiceImpl(NoteRepository noteRepository, NoteMapper noteMapper, NoteVersionRepository noteVersionRepository, NotePolicyService notePolicyService, UserPolicyService userPolicyService, RedisService redisService, AttributionService attributionService, NotePersistenceService notePersistenceService) {
        this.noteRepository = noteRepository;
        this.noteMapper = noteMapper;
        this.noteVersionRepository = noteVersionRepository;
        this.notePolicyService = notePolicyService;
        this.userPolicyService = userPolicyService;
        this.redisService = redisService;
        this.attributionService = attributionService;
        this.notePersistenceService = notePersistenceService;
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

    @Override
    public List<TextOperation> fetchRevisionLog(String actorEmail, UUID noteId) {
        Note note = notePolicyService.findNoteById(noteId);

        if (notePolicyService.resolveRole(actorEmail, note) == null) {
            if (!note.getVisibility().equals(NoteVisibility.PUBLIC)) {
                log.warn("Note with id={} visibility is not public", noteId);
                throw new BadRequestException("Note is not visible to the public");
            }
        }

        return note.getRevisionLog();
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

        Delta initialDelta = payload.initialDelta() != null ? payload.initialDelta() : new Delta();
        boolean hasContent = initialDelta.ops != null && !initialDelta.ops.isEmpty();

        NoteVersion noteCopyVersion  = new NoteVersion(
                null,
                newNote,
                hasContent ? initialDelta : new Delta(),
                0,
                "Note copy",
                0
        );
        noteCopyVersion  = noteVersionRepository.save(noteCopyVersion );
        newNote.getNoteVersions().add(noteCopyVersion );

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

        redisService.initializeNote(actorEmail, newNote.getId());
        redisService.addCollaboratorToNote(newNote.getId(), actorEmail);

        if (newNote.getUser() == null) {
            newNote.setUser(user);
        }

        return noteMapper.toDto(newNote, actorEmail);
    }

    @Override
    public JoinNoteResponse joinNote(UUID userId, String actorEmail, UUID noteId) {
        notePolicyService.validateEditor(actorEmail, noteId);

        redisService.initializeNote(actorEmail, noteId);
        NoteDto note = redisService.getNote(noteId);
        NoteVersionDto noteVersion = redisService.getNoteVersion(noteId);

        boolean isReviewing = redisService.isReviewInProgress(noteId, note.ownerEmail());

//        reviewInProgressNotifier.notifyReviewInProgress(noteId, new ReviewInProgressResponsePayload(noteId, true));

        redisService.addCollaboratorToNote(noteId, actorEmail);

        Map<Object, Object> collaborators = redisService.getCollaborators(noteId);
        collaboratorCountNotifier.notifyCount(noteId, new CollaboratorsPayload(collaborators));

        return new JoinNoteResponse(collaborators, noteVersion.masterDelta(), noteVersion.revision(), isReviewing);
    }

    @Override
    public ReviewProjection buildAttribution(String actorEmail, UUID noteId) {
        notePolicyService.validateOwner(actorEmail, noteId);
        NoteDto note = redisService.getNote(noteId);

        List<TextOperation> committedTextOps = note.revisionLog().stream()
                .filter(textOp -> textOp.getState().equals(OpState.COMMITTED))
                .toList();
        List<TextOperation> pendingTextOps = note.revisionLog().stream()
                .filter(textOp -> textOp.getState().equals(OpState.PENDING))
                .toList();

        return attributionService.buildReviewProjection(actorEmail, noteId, committedTextOps, pendingTextOps);
    }

    @Override
    public void changeCursor(CursorDto cursorDto, UUID noteId, String actorEmail) {
        cursorNotifier.notifyCursorChange(noteId, new CursorPayload(actorEmail, cursorDto.position()));
    }

    @Override
    public void startReview(String actorEmail, UUID noteId) {
        notePolicyService.validateOwner(actorEmail,noteId);
        redisService.setReviewInProgress(noteId, actorEmail, "true");
        reviewInProgressNotifier.notifyReviewInProgress(noteId, new ReviewInProgressResponsePayload(noteId, true));
    }

    @Override
    public void applyReviewChanges(String actorEmail, UUID noteId, ReviewNotePayload payload) {
        notePolicyService.validateOwner(actorEmail, noteId);

        NoteDto note = redisService.getNote(noteId);
        NoteVersionDto noteVersion = redisService.getNoteVersion(noteId);

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
                accumulator.applyReviewDecisionsToRevisionLog(note.revisionLog());

        NoteVersionDto newNoteVersion = noteVersion;

        if (
                result.changed()
                        && result.committedMasterDelta() != null
                        && result.committedMasterDelta().ops != null
                        && !result.committedMasterDelta().ops.isEmpty()
        ) {
            Delta newMasterDelta =
                    noteVersion.masterDelta().compose(result.committedMasterDelta());

            newNoteVersion = new NoteVersionDto(
                    noteVersion.id(),
                    newMasterDelta,
                    noteVersion.revision() + 1,
                    noteVersion.comment(),
                    noteVersion.versionNumber(),
                    noteVersion.createdAt()
            );
        }

        redisService.updateNote(note, newNoteVersion);
        saveNote(actorEmail, noteId);
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
    }

    @Override
    public void changeNoteVisibility(String userEmail, UUID noteId, NoteVisibility visibility) {
        Note note = notePolicyService.validateSuper(userEmail, noteId);
        note.setVisibility(visibility);
        noteRepository.save(note);
    }
}