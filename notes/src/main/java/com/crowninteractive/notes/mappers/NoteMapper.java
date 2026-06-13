package com.crowninteractive.notes.mappers;

import com.crowninteractive.notes.dto.note.NoteDto;
import com.crowninteractive.notes.entities.note.Note;

public interface NoteMapper {
    Note fromDto(NoteDto noteDto);
    NoteDto toDto(Note note, String actorEmail);
    NoteDto toDtoRestricted();
}
