package com.example.notes.mappers.impl;

import com.example.notes.dto.noteAccess.NoteAccessDto;
import com.example.notes.entities.note.Note;
import com.example.notes.entities.noteAccess.NoteAccess;
import com.example.notes.mappers.NoteAccessMapper;
import com.example.notes.services.impl.NotePolicyService;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class NoteAccessMapperImpl implements NoteAccessMapper {
    private final NotePolicyService notePolicyService;

    public NoteAccessMapperImpl(NotePolicyService notePolicyService) {
        this.notePolicyService = notePolicyService;
    }

    @Override
    public NoteAccess fromDto(NoteAccessDto noteAccessDto, UUID noteId) {
        Note note = noteId != null ? notePolicyService.findNoteById(noteId) : null;
        return new NoteAccess(
                noteAccessDto.id(),
                note,
                noteAccessDto.email(),
                noteAccessDto.role()
        );
    }

    @Override
    public NoteAccessDto toDto(NoteAccess noteAccess) {
        return new NoteAccessDto(
                noteAccess.getId(),
                noteAccess.getEmail(),
                noteAccess.getRole()
        );
    }
}
