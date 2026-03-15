package com.example.notes.services.impl;

import com.example.notes.dto.noteVersion.NoteVersionDto;
import com.example.notes.entities.note.Note;
import com.example.notes.entities.noteAccess.NoteAccess;
import com.example.notes.entities.noteAccess.NoteAccessRole;
import com.example.notes.entities.noteVersion.NoteVersion;
import com.example.notes.exceptions.BadRequestException;
import com.example.notes.mappers.NoteVersionMapper;
import com.example.notes.repositories.NoteRepository;
import com.example.notes.repositories.NoteVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class NotePolicyService {
    private final NoteRepository noteRepository;
    private final NoteVersionRepository noteVersionRepository;
    private final NoteVersionMapper noteVersionMapper;

    private static final Logger log =
            LoggerFactory.getLogger(NotePolicyService.class);

    public NotePolicyService(NoteRepository noteRepository, NoteVersionRepository noteVersionRepository, NoteVersionMapper noteVersionMapper) {
        this.noteRepository = noteRepository;
        this.noteVersionRepository = noteVersionRepository;
        this.noteVersionMapper = noteVersionMapper;
    }

    public Note findNoteById(UUID noteId) {
        return noteRepository.findById(noteId)
                .orElseThrow(() -> {
                    log.warn("Note not found id={}", noteId);
                    return new BadRequestException(
                            "Note with this id does not exist."
                    );
                });
    }

    public NoteVersion findNoteVersionByNoteId(UUID noteId) {
        Note note = findNoteById(noteId);
        return noteVersionRepository.findByNote_IdAndVersionNumber(noteId, note.getCurrentNoteVersionNumber())
                .orElseThrow(() -> {
                    log.warn("Note version not found id={}", note.getCurrentNoteVersionNumber());
                    return new BadRequestException(
                            "Note version with this id does not exist."
                    );
                });
    }

    public NoteAccessRole resolveRole(String actorEmail, Note note) {
        NoteAccessRole accessRole = null;

        if (note == null) {
            log.warn("Note is required");
            throw new BadRequestException("Note is required");
        }

        if (note.getUser().getEmail().equals(actorEmail)) {
            return NoteAccessRole.OWNER;
        }

        for (NoteAccess noteAccess : note.getNoteAccesses()) {
            if (noteAccess.getEmail().equals(actorEmail)) {
                accessRole = noteAccess.getRole();
                break;
            }
        }
        return accessRole;
    }

    public Note validateOwner(String userEmail, UUID noteId) {
        Note note = findNoteById(noteId);

        if (!note.getUser().getEmail().equals(userEmail)) {
            log.warn("User with the email={} is not the owner of this note", userEmail);
            throw new BadRequestException("User with the email is not the owner of this note");
        }
        return note;
    }

    public Note validateSuper(String userEmail, UUID noteId) {
        Note note = findNoteById(noteId);
        NoteAccessRole accessRole = resolveRole(userEmail, note);

        if (!Set.of(NoteAccessRole.OWNER, NoteAccessRole.SUPER).contains(accessRole)) {
            log.warn("User with the email={} does not have super user access control of this note", userEmail);
            throw new BadRequestException("User with the email  does not have super user access control of this note");
        }
        return note;
    }

    @Transactional
    public Note validateEditor(String userEmail, UUID noteId) {
        Note note = findNoteById(noteId);
        NoteAccessRole accessRole = resolveRole(userEmail, note);

        if (!Set.of(NoteAccessRole.OWNER, NoteAccessRole.SUPER,  NoteAccessRole.EDITOR).contains(accessRole)) {
            log.warn("User with the email={} is not allowed to edit this note", userEmail);
            throw new BadRequestException("User with the email is not allowed to edit this note");
        }
        return note;
    }

    public Note validateViewer(String userEmail, UUID noteId) {
        Note note = findNoteById(noteId);
        NoteAccessRole accessRole = resolveRole(userEmail, note);

        if (!Set.of(NoteAccessRole.OWNER, NoteAccessRole.SUPER,  NoteAccessRole.EDITOR, NoteAccessRole.VIEWER).contains(accessRole)) {
            log.warn("User with the email={} is not allowed to view this note", userEmail);
            throw new BadRequestException("User with the email is not allowed to view this note");
        }
        return note;
    }

    public NoteVersionDto getCurrentNoteVersion(UUID currentNoteVersionId) {
        Optional<NoteVersion> noteVersion = noteVersionRepository.findById(currentNoteVersionId);
        return noteVersion.map(noteVersionMapper::toDto).orElse(null);
    }
}
