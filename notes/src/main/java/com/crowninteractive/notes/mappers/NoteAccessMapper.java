package com.crowninteractive.notes.mappers;

import com.crowninteractive.notes.dto.noteAccess.NoteAccessDto;
import com.crowninteractive.notes.entities.noteAccess.NoteAccess;

public interface NoteAccessMapper {
    NoteAccess fromDto(NoteAccessDto noteAccessDto, String noteId);
    NoteAccessDto toDto(NoteAccess noteAccess);
}
