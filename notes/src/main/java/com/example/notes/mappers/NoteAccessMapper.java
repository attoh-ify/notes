package com.example.notes.mappers;

import com.example.notes.dto.noteAccess.NoteAccessDto;
import com.example.notes.entities.noteAccess.NoteAccess;

import java.util.UUID;

public interface NoteAccessMapper {
    NoteAccess fromDto(NoteAccessDto noteAccessDto, UUID noteId);
    NoteAccessDto toDto(NoteAccess noteAccess);
}
