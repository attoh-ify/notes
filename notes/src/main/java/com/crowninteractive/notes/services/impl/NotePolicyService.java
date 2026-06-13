package com.crowninteractive.notes.services.impl;

import com.crowninteractive.notes.dto.noteVersion.NoteVersionDto;
import com.crowninteractive.notes.entities.note.Note;
import com.crowninteractive.notes.entities.noteAccess.NoteAccess;
import com.crowninteractive.notes.entities.noteAccess.NoteAccessRole;
import com.crowninteractive.notes.entities.noteVersion.NoteVersion;
import com.crowninteractive.notes.exceptions.BadRequestException;
import com.crowninteractive.notes.mappers.NoteVersionMapper;
import com.crowninteractive.notes.repositories.NoteRepository;
import com.crowninteractive.notes.repositories.NoteVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;

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

    public Note findNoteById(String noteId) {
        return noteRepository.findByNoteId(noteId)
                .orElseThrow(() -> {
                    log.warn("Note not found id={}", noteId);
                    return new BadRequestException(
                            "Note with this id does not exist."
                    );
                });
    }

    public NoteVersion findNoteCopy(String noteId) {
        return noteVersionRepository.findByNote_NoteIdAndVersionNumber(noteId, 0)
                .orElseThrow(() -> {
                    log.warn("Note copy not found");
                    return new BadRequestException(
                            "Note copy does not exist."
                    );
                });
    }

    public NoteAccessRole resolveRole(String actorEmail, Note note) {
        NoteAccessRole accessRole = NoteAccessRole.RESTRICTED;

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

    public Note validateOwner(String userEmail, String noteId) {
        Note note = findNoteById(noteId);

        if (!note.getUser().getEmail().equals(userEmail)) {
            log.warn("User with the email={} is not the owner of this note", userEmail);
            throw new BadRequestException("User with the email is not the owner of this note");
        }
        return note;
    }

    public Note validateSuper(String userEmail, String noteId) {
        Note note = findNoteById(noteId);
        NoteAccessRole accessRole = resolveRole(userEmail, note);

        if (!Set.of(NoteAccessRole.OWNER, NoteAccessRole.SUPER).contains(accessRole)) {
            log.warn("User with the email={} does not have super user access control of this note", userEmail);
            throw new BadRequestException("User with the email  does not have super user access control of this note");
        }
        return note;
    }

    @Transactional
    public Note validateEditor(String userEmail, String noteId) {
        Note note = findNoteById(noteId);
        NoteAccessRole accessRole = resolveRole(userEmail, note);

        if (!Set.of(NoteAccessRole.OWNER, NoteAccessRole.SUPER,  NoteAccessRole.EDITOR).contains(accessRole)) {
            log.warn("User with the email={} is not allowed to edit this note", userEmail);
            throw new BadRequestException("User with the email is not allowed to edit this note");
        }
        return note;
    }

    public Note validateViewer(String userEmail, String noteId) {
        Note note = findNoteById(noteId);
        NoteAccessRole accessRole = resolveRole(userEmail, note);

        if (!Set.of(NoteAccessRole.OWNER, NoteAccessRole.SUPER,  NoteAccessRole.EDITOR, NoteAccessRole.VIEWER).contains(accessRole)) {
            log.warn("User with the email={} is not allowed to view this note", userEmail);
            throw new BadRequestException("User with the email is not allowed to view this note");
        }
        return note;
    }

    public NoteVersionDto getCurrentNoteVersion(String currentNoteVersionId) {
        Optional<NoteVersion> noteVersion = noteVersionRepository.findByNoteVersionId(currentNoteVersionId);
        return noteVersion.map(noteVersionMapper::toDto).orElse(null);
    }
}
