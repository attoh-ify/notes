package com.crowninteractive.notes.mappers.impl;

import com.crowninteractive.notes.dto.noteAccess.NoteAccessDto;
import com.crowninteractive.notes.entities.note.Note;
import com.crowninteractive.notes.entities.noteAccess.NoteAccess;
import com.crowninteractive.notes.mappers.NoteAccessMapper;
import com.crowninteractive.notes.services.impl.NotePolicyService;
import org.springframework.stereotype.Component;

@Component
public class NoteAccessMapperImpl implements NoteAccessMapper {
    private final NotePolicyService notePolicyService;

    public NoteAccessMapperImpl(NotePolicyService notePolicyService) {
        this.notePolicyService = notePolicyService;
    }

    @Override
    public NoteAccess fromDto(NoteAccessDto noteAccessDto, String noteId) {
        Note note = noteId != null ? notePolicyService.findNoteById(noteId) : null;
        return new NoteAccess(
                noteAccessDto.getId(),
                noteAccessDto.getNoteAccessId(),
                note,
                noteAccessDto.getEmail(),
                noteAccessDto.getRole()
        );
    }

    @Override
    public NoteAccessDto toDto(NoteAccess noteAccess) {
        return new NoteAccessDto(
                noteAccess.getId(),
                noteAccess.getNoteAccessId(),
                noteAccess.getEmail(),
                noteAccess.getRole()
        );
    }
}
