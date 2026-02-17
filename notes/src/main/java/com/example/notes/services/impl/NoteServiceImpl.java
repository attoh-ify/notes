package com.example.notes.services.impl;

import com.example.notes.dto.message_payload.CollaboratorsPayload;
import com.example.notes.dto.note.CreateNotePayload;
import com.example.notes.dto.note.JoinNoteResponse;
import com.example.notes.dto.note.NoteDto;
import com.example.notes.dto.ot.Delta;
import com.example.notes.entities.note.Note;
import com.example.notes.entities.note.NoteVisibility;
import com.example.notes.entities.noteVersion.NoteVersion;
import com.example.notes.entities.user.User;
import com.example.notes.exceptions.BadRequestException;
import com.example.notes.mappers.NoteMapper;
import com.example.notes.notifier.CollaboratorCountNotifier;
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
                throw new BadRequestException("Note visibility is not public");
            }
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
                null,
                null,
                null
        );
        noteRepository.save(newNote);

        NoteVersion firstNoteVersion = new NoteVersion(
                null,
                newNote,
                new Delta(),
                0,
                user.getId(),
                1
        );
        noteVersionRepository.save(firstNoteVersion);

        newNote.setCurrentNoteVersion(firstNoteVersion.getId());
        newNote.setNoteVersions(new ArrayList<>());
        newNote.getNoteVersions().add(firstNoteVersion);

        noteRepository.save(newNote);

        redisService.initializeNote(actorEmail, newNote.getId());
        redisService.addCollaboratorToNote(newNote.getId(), actorEmail);

        return noteMapper.toDto(newNote, actorEmail);
    }

    @Override
    public JoinNoteResponse joinNote(UUID userId, String actorEmail, UUID noteId) {
        redisService.initializeNote(actorEmail, noteId);
        NoteVersion noteVersion = redisService.getNoteVersion(noteId);

        redisService.addCollaboratorToNote(noteId, actorEmail);

        List<String> collaborators = redisService.getCollaborators(noteId);
        collaboratorCountNotifier.notifyCount(noteId, new CollaboratorsPayload(collaborators));

        return new JoinNoteResponse(collaborators, noteVersion.getMasterDelta(), noteVersion.getRevision());
    }

    @Override
    public void saveNote(String actorEmail, UUID noteId) {
        Note note = notePolicyService.validateEditor(actorEmail, noteId);
        NoteVersion noteVersion = noteVersionRepository.findById(note.getCurrentNoteVersion())
                .orElseThrow(() -> {
                    log.warn("Note version with id={} not found", note.getCurrentNoteVersion());
                    return new BadRequestException("Note version not found");
                });

        Note storedNote = redisService.getNote(noteId);
        NoteVersion storedNoteVersion = redisService.getNoteVersion(noteId);

        noteVersion.setMasterDelta(storedNoteVersion.getMasterDelta());
        noteVersion.setRevision(storedNoteVersion.getRevision());
        noteVersionRepository.save(noteVersion);

        note.setRevisionLog(storedNote.getRevisionLog());
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
