package com.example.notes.services.impl;

import com.example.notes.dto.message_payload.CollaboratorsPayload;
import com.example.notes.dto.message_payload.CursorPayload;
import com.example.notes.dto.message_payload.ReviewInProgressResponsePayload;
import com.example.notes.dto.note.CreateNotePayload;
import com.example.notes.dto.note.CursorDto;
import com.example.notes.dto.note.JoinNoteResponse;
import com.example.notes.dto.note.NoteDto;
import com.example.notes.dto.noteVersion.NoteVersionDto;
import com.example.notes.dto.ot.Delta;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

        NoteVersion firstNoteVersion = new NoteVersion(
                null,
                newNote,
                new Delta(),
                0,
                "Note copy",
                0
        );

        noteVersionRepository.save(firstNoteVersion);

        newNote.getNoteVersions().add(firstNoteVersion);

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

        if (!actorEmail.equals(note.ownerEmail()) && redisService.isReviewInProgress(noteId, note.ownerEmail())) {
            reviewInProgressNotifier.notifyReviewInProgress(noteId, new ReviewInProgressResponsePayload(noteId, true));
            return null;
        }

        redisService.addCollaboratorToNote(noteId, actorEmail);

        Map<Object, Object> collaborators = redisService.getCollaborators(noteId);
        collaboratorCountNotifier.notifyCount(noteId, new CollaboratorsPayload(collaborators));

        return new JoinNoteResponse(collaborators, noteVersion.masterDelta(), noteVersion.revision());
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
    public void applyReviewChanges(String actorEmail, UUID noteId, List<TextOperation> textOps) {
        notePolicyService.validateOwner(actorEmail, noteId);
        NoteDto note = redisService.getNote(noteId);
        NoteVersionDto noteVersion = redisService.getNoteVersion(noteId);
        Delta masterDelta = noteVersion.masterDelta();

        for (int i = 0; i < textOps.size(); i++) {
            TextOperation newTextOp = new TextOperation(
                    textOps.get(i).getDelta(),
                    actorEmail,
                    noteVersion.revision() + i,
                    OpState.INVERSE,
                    textOps.get(i).getCreatedAt()
            );

            masterDelta.compose(textOps.get(i).getDelta());
            note.revisionLog().add(newTextOp);
        }

        NoteVersionDto newNoteVersion = new NoteVersionDto(
                noteVersion.id(),
                masterDelta,
                noteVersion.revision() + textOps.size(),
                noteVersion.comment(),
                noteVersion.versionNumber(),
                noteVersion.createdAt()
        );

        note.revisionLog().stream().peek(textOp -> {
            if (textOp.getState().equals(OpState.PENDING)) {
                textOp.setState(OpState.COMMITED);
            }
        }).toList();
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
}
