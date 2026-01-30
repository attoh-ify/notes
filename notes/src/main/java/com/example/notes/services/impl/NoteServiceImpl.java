package com.example.notes.services.impl;

import com.example.notes.dto.note.NoteDto;
import com.example.notes.entities.note.Note;
import com.example.notes.entities.note.NoteVisibility;
import com.example.notes.entities.noteVersion.NoteVersion;
import com.example.notes.entities.user.User;
import com.example.notes.exceptions.BadRequestException;
import com.example.notes.mappers.NoteMapper;
import com.example.notes.repositories.NoteRepository;
import com.example.notes.repositories.NoteVersionRepository;
import com.example.notes.services.NoteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class NoteServiceImpl implements NoteService {
    private final NoteRepository noteRepository;
    private final NoteMapper noteMapper;
    private final NoteVersionRepository noteVersionRepository;
    private final NotePolicyService notePolicyService;
    private final UserPolicyService userPolicyService;

    private static final Logger log =
            LoggerFactory.getLogger(NoteServiceImpl.class);

    public NoteServiceImpl(NoteRepository noteRepository, NoteMapper noteMapper, NoteVersionRepository noteVersionRepository, NotePolicyService notePolicyService, UserPolicyService userPolicyService) {
        this.noteRepository = noteRepository;
        this.noteMapper = noteMapper;
        this.noteVersionRepository = noteVersionRepository;
        this.notePolicyService = notePolicyService;
        this.userPolicyService = userPolicyService;
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
    public NoteDto createNote(String actorEmail) {
        User user = userPolicyService.userExists(actorEmail);

        Note newNote = new Note(
                null,
                user,
                "Untitled",
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
                "",
                0,
                user.getId(),
                1
        );
        noteVersionRepository.save(firstNoteVersion);

        newNote.setCurrentNoteVersion(firstNoteVersion.getId());
        newNote.setNoteVersions(new ArrayList<>());
        newNote.getNoteVersions().add(firstNoteVersion);

        noteRepository.save(newNote);
        return noteMapper.toDto(newNote, actorEmail);
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
