package com.example.notes.mappers.impl;

import com.example.notes.dto.note.NoteDto;
import com.example.notes.entities.note.Note;
import com.example.notes.entities.note.NoteVisibility;
import com.example.notes.entities.noteAccess.NoteAccessRole;
import com.example.notes.entities.user.User;
import com.example.notes.exceptions.BadRequestException;
import com.example.notes.mappers.NoteMapper;
import com.example.notes.repositories.UserRepository;
import com.example.notes.services.impl.NotePolicyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class NoteMapperImpl implements NoteMapper {
    private final UserRepository userRepository;
    private final NotePolicyService notePolicyService;

    private static final Logger log =
            LoggerFactory.getLogger(NoteMapperImpl.class);

    public NoteMapperImpl(UserRepository userRepository, NotePolicyService notePolicyService) {
        this.userRepository = userRepository;
        this.notePolicyService = notePolicyService;
    }

    @Override
    public Note fromDto(NoteDto noteDto) {
        User user = userRepository.findByEmail(noteDto.ownerEmail())
                .orElseThrow(() -> {
                    log.warn("User with email {} not found", noteDto.ownerEmail());
                    return new BadRequestException("User with email not found");
                });
        return new Note(
                noteDto.id(),
                user,
                noteDto.title(),
                null,
                noteDto.visibility(),
                null,
                noteDto.currentNoteVersionNumber(),
                null,
                noteDto.isReviewing()
        );
    }

    @Override
    public NoteDto toDto(Note note, String actorEmail) {
        NoteAccessRole accessRole = notePolicyService.resolveRole(actorEmail, note);
        return new NoteDto(
                note.getId(),
                note.getUser().getEmail(),
                note.getTitle(),
                null,
                note.getVisibility(),
                accessRole,
                note.getCurrentNoteVersionNumber(),
                note.isReviewing(),
                note.getCreatedAt(),
                note.getUpdatedAt()
        );
    }

    @Override
    public NoteDto toDtoRestricted() {
        return new NoteDto(
                null,
                null,
                null,
                null,
                NoteVisibility.PRIVATE,
                NoteAccessRole.RESTRICTED,
                0,
                false,
                null,
                null
        );
    }
}
