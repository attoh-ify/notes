package com.example.notes.services.impl;

import com.example.notes.dto.attribution.SuggestionSlice;
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
import com.example.notes.entities.noteVersion.NoteVersion;
import com.example.notes.entities.user.User;
import com.example.notes.exceptions.BadRequestException;
import com.example.notes.mappers.NoteMapper;
import com.example.notes.notifier.CollaboratorCountNotifier;
import com.example.notes.notifier.CursorNotifier;
import com.example.notes.notifier.ReviewInProgressNotifier;
import com.example.notes.repositories.NoteRepository;
import com.example.notes.repositories.NoteVersionRepository;
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

    private static final Logger log =
            LoggerFactory.getLogger(NoteServiceImpl.class);

    public NoteServiceImpl(NoteRepository noteRepository, NoteMapper noteMapper, NoteVersionRepository noteVersionRepository, NotePolicyService notePolicyService, UserPolicyService userPolicyService, RedisService redisService) {
        this.noteRepository = noteRepository;
        this.noteMapper = noteMapper;
        this.noteVersionRepository = noteVersionRepository;
        this.notePolicyService = notePolicyService;
        this.userPolicyService = userPolicyService;
        this.redisService = redisService;
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

        if (notePolicyService.resolveRole(actorEmail, note) == null) {
            if (!note.getVisibility().equals(NoteVisibility.PUBLIC)) {
                log.warn("Note with id={} visibility is not public", noteId);
                throw new BadRequestException("Note is not visible to the public");
            }
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

        NoteVersion firstNoteVersion = new NoteVersion(
                null,
                newNote,
                hasContent ? initialDelta : new Delta(),
                0,
                "Note copy",
                0
        );
        noteVersionRepository.save(firstNoteVersion);
        newNote.getNoteVersions().add(firstNoteVersion);

        if (hasContent) {
            NoteVersion masterVersion = new NoteVersion(
                    null,
                    newNote,
                    initialDelta,
                    0,
                    "Imported from document",
                    1
            );
            noteVersionRepository.save(masterVersion);
            newNote.getNoteVersions().add(masterVersion);
        }

        noteRepository.save(newNote);
        redisService.initializeNote(actorEmail, newNote.getId());
        redisService.addCollaboratorToNote(newNote.getId(), actorEmail);

        if (newNote.getUser() == null) {
            newNote.setUser(user);
        }

        return noteMapper.toDto(newNote, actorEmail);
    }

    @Override
    public JoinNoteResponse joinNote(UUID userId, String actorEmail, UUID noteId) {
        redisService.initializeNote(actorEmail, noteId);
        NoteDto note = redisService.getNote(noteId);
        NoteVersionDto noteVersion = redisService.getNoteVersion(noteId);

        if (redisService.isReviewInProgress(noteId, note.ownerEmail())) {
            reviewInProgressNotifier.notifyReviewInProgress(noteId, new ReviewInProgressResponsePayload(noteId, true));
            return new JoinNoteResponse(null, null, 0, true);
        }

        redisService.addCollaboratorToNote(noteId, actorEmail);

        Map<Object, Object> collaborators = redisService.getCollaborators(noteId);
        collaboratorCountNotifier.notifyCount(noteId, new CollaboratorsPayload(collaborators));

        return new JoinNoteResponse(collaborators, noteVersion.masterDelta(), noteVersion.revision(), false);
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
        Delta masterDelta = noteVersion.masterDelta();
        NoteVersionDto newNoteVersion = noteVersion;

        if (payload.rejectedChange() != null
                && payload.rejectedChange().getDelta() != null
                && !payload.rejectedChange().getDelta().ops.isEmpty()) {

            TextOperation newTextOp = new TextOperation(
                    payload.rejectedChange().getDelta(),
                    actorEmail,
                    noteVersion.revision() + 1,
                    OpState.COMMITTED,
                    payload.rejectedChange().getCreatedAt()
            );

            note.revisionLog().add(newTextOp);

            newNoteVersion = new NoteVersionDto(
                    noteVersion.id(),
                    masterDelta.compose(payload.rejectedChange().getDelta()),
                    noteVersion.revision() + 1,
                    noteVersion.comment(),
                    noteVersion.versionNumber(),
                    noteVersion.createdAt()
            );
        }

        Map<String, List<SuggestionSlice>> acceptedByOpId =
                payload.acceptedReferences() == null
                        ? new LinkedHashMap<>()
                        : payload.acceptedReferences().stream()
                        .filter(s -> s.getRef() != null)
                        .collect(Collectors.groupingBy(
                                s -> s.getRef().opId(),
                                LinkedHashMap::new,
                                Collectors.toList()
                        ));

        for (TextOperation textOp : new ArrayList<>(note.revisionLog())) {
            List<SuggestionSlice> slicesForOp = acceptedByOpId.get(textOp.getOpId());
            if (slicesForOp == null || slicesForOp.isEmpty()) continue;

            Delta committedDelta = new Delta();
            Delta remainingDelta = new Delta();

            for (int i = 0; i < textOp.getDelta().ops.size(); i++) {
                Op op = textOp.getDelta().ops.get(i);

                int finalI = i;
                List<SuggestionSlice> acceptedSlicesForComponent = slicesForOp.stream()
                        .filter(s -> Objects.equals(s.getRef().componentIndex(), finalI))
                        .sorted(Comparator.comparingInt(SuggestionSlice::getComponentStart))
                        .toList();

                if (acceptedSlicesForComponent.isEmpty()) {
                    appendOpToRemainingAndRetainInCommitted(op, remainingDelta, committedDelta);
                    continue;
                }

                applyAcceptedSlicesForComponent(
                        op,
                        acceptedSlicesForComponent,
                        committedDelta,
                        remainingDelta
                );
            }

            if (remainingDelta.ops.isEmpty()) {
                textOp.setState(OpState.COMMITTED);
            } else {
                TextOperation committedOp = new TextOperation(
                        committedDelta,
                        textOp.getActorEmail(),
                        textOp.getRevision(),
                        OpState.COMMITTED,
                        textOp.getCreatedAt()
                );

                textOp.setDelta(remainingDelta);

                int textOpIndex = note.revisionLog().indexOf(textOp);
                note.revisionLog().add(textOpIndex, committedOp);
            }
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
        Note note = notePolicyService.validateEditor(actorEmail, noteId);
        NoteVersion noteVersion = noteVersionRepository.findByNote_IdAndVersionNumber(noteId, note.getCurrentNoteVersionNumber())
                .orElseThrow(() -> {
                    log.warn("Note version with id={} not found", note.getCurrentNoteVersionNumber());
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

    private void appendOpToRemainingAndRetainInCommitted(
            Op op,
            Delta remainingDelta,
            Delta committedDelta
    ) {
        if (op.isDelete()) {
            remainingDelta.delete(op.getDelete());
        } else if (op.isInsert()) {
            remainingDelta.insert(op.getInsert(), op.getAttributes());
            committedDelta.retain(op.length(), null);
        } else if (op.isRetain()) {
            if (op.getAttributes() != null && !op.getAttributes().isEmpty()) {
                remainingDelta.retain(op.getRetain(), op.getAttributes());
                committedDelta.retain(op.getRetain(), null);
            } else {
                remainingDelta.retain(op.getRetain(), null);
                committedDelta.retain(op.getRetain(), null);
            }
        }
    }

    private void applyAcceptedSlicesForComponent(
            Op op,
            List<SuggestionSlice> acceptedSlices,
            Delta committedDelta,
            Delta remainingDelta
    ) {
        int cursor = 0;
        int componentLength = op.length();

        for (SuggestionSlice slice : acceptedSlices) {
            int start = Math.max(0, Math.min(slice.getComponentStart(), componentLength));
            int end = Math.max(start, Math.min(slice.getComponentStart() + slice.getLength(), componentLength));

            if (start > cursor) {
                appendUnacceptedPart(op, cursor, start - cursor, committedDelta, remainingDelta);
            }

            if (end > start) {
                appendAcceptedPart(op, start, end - start, committedDelta, remainingDelta);
            }

            cursor = Math.max(cursor, end);
        }

        if (cursor < componentLength) {
            appendUnacceptedPart(op, cursor, componentLength - cursor, committedDelta, remainingDelta);
        }
    }

    private String safeSubstring(String text, int start, int length) {
        int safeStart = Math.max(0, Math.min(start, text.length()));
        int safeEnd = Math.max(safeStart, Math.min(start + length, text.length()));
        return text.substring(safeStart, safeEnd);
    }

    private void appendAcceptedPart(
            Op op,
            int start,
            int length,
            Delta committedDelta,
            Delta remainingDelta
    ) {
        if (length <= 0) return;

        if (op.isInsert()) {
            String text = String.valueOf(op.getInsert());

            String safe = safeSubstring(text, start, length);

            if (!safe.isEmpty()) {
                committedDelta.insert(safe, op.getAttributes());
                remainingDelta.retain(safe.length(), null);
            }

        } else if (op.isDelete()) {
            committedDelta.delete(Math.min(length, op.getDelete()));
        } else if (op.isRetain()) {
            committedDelta.retain(length, op.getAttributes());
            remainingDelta.retain(length, null);
        }
    }

    private void appendUnacceptedPart(
            Op op,
            int start,
            int length,
            Delta committedDelta,
            Delta remainingDelta
    ) {
        if (length <= 0) return;

        if (op.isInsert()) {
            String text = String.valueOf(op.getInsert());

            String safe = safeSubstring(text, start, length);

            if (!safe.isEmpty()) {
                remainingDelta.insert(safe, op.getAttributes());
                committedDelta.retain(safe.length(), null);
            }

        } else if (op.isDelete()) {
            remainingDelta.delete(Math.min(length, op.getDelete()));
        } else if (op.isRetain()) {
            if (op.getAttributes() != null && !op.getAttributes().isEmpty()) {
                remainingDelta.retain(length, op.getAttributes());
                committedDelta.retain(length, null);
            } else {
                remainingDelta.retain(length, null);
                committedDelta.retain(length, null);
            }
        }
    }
}