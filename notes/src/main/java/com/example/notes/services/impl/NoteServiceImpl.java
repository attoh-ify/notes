package com.example.notes.services.impl;

import com.example.notes.dto.message_payload.CollaborationCountPayload;
import com.example.notes.dto.note.CreateNotePayload;
import com.example.notes.dto.note.DocumentModel;
import com.example.notes.dto.note.NoteDto;
import com.example.notes.dto.response.ResponseDto;
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
import com.example.notes.shared.document_store.NoteStore;
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
    private final NoteStore  noteStore;

    private static final Logger log =
            LoggerFactory.getLogger(NoteServiceImpl.class);

    public NoteServiceImpl(NoteRepository noteRepository, NoteMapper noteMapper, NoteVersionRepository noteVersionRepository, NotePolicyService notePolicyService, UserPolicyService userPolicyService, NoteStore noteStore) {
        this.noteRepository = noteRepository;
        this.noteMapper = noteMapper;
        this.noteVersionRepository = noteVersionRepository;
        this.notePolicyService = notePolicyService;
        this.userPolicyService = userPolicyService;
        this.noteStore = noteStore;
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
//                new ArrayList<>(),
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

    @Override
    public Object joinNote(UUID userId, String actorEmail, UUID noteId) {
        DocumentModel doc = noteStore.getNoteFromNoteId(noteId);
        if (noteStore.getNoteFromNoteId(noteId) == null) {
            System.out.println("Note with id=" + noteId + " not found");
            noteStore.addEmptyNote(userId, noteId);
            Note note = notePolicyService.validateEditor(actorEmail, noteId);
            NoteVersion noteVersion = noteVersionRepository.findById(note.getCurrentNoteVersion())
                    .orElseThrow(() -> {
                        log.warn("Note version not found with id={}", note.getCurrentNoteVersion());
                        return new BadRequestException("Not version not found");
                    });
            doc.setDocText(noteVersion.getContent());
            System.out.print("Note version text: " + noteVersion.getContent());
            System.out.println("Doc text: " + doc.getDocText());
        }

        noteStore.addCollaboratorToNote(userId, noteId);

        collaboratorCountNotifier.notifyCount(noteId, new CollaborationCountPayload(noteStore.getNoteFromNoteId(noteId).getCollaboratorCount()));

        return Map.of(
                "collaboratorCount", doc.getCollaboratorCount(),
                "text", doc.getDocText(),
                "revision", doc.getRevision()
        );
    }

    @Override
    public void saveNote(String actorEmail, UUID noteId) {
        Note note = notePolicyService.validateEditor(actorEmail, noteId);
        NoteVersion noteVersion = noteVersionRepository.findById(note.getCurrentNoteVersion())
                .orElseThrow(() -> {
                    log.warn("Note version with id={} not found", note.getCurrentNoteVersion());
                    return new BadRequestException("Note version not found");
                });
        System.out.println(noteVersion.getContent());
        DocumentModel doc = noteStore.getNoteFromNoteId(noteId);
        noteVersion.setContent(doc.getDocText());
        noteVersion.setRevision(doc.getRevision());
        noteVersionRepository.save(noteVersion);
        System.out.println(noteVersion.getContent());
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
