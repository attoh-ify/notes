package com.example.notes.mappers;

import com.example.notes.dto.note.NoteDto;
import com.example.notes.entities.note.Note;

public interface NoteMapper {
    Note fromDto(NoteDto noteDto);
    NoteDto toDto(Note note);
}
