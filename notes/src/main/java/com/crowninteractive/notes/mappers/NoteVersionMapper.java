package com.crowninteractive.notes.mappers;

import com.crowninteractive.notes.dto.noteVersion.NoteVersionDto;
import com.crowninteractive.notes.entities.noteVersion.NoteVersion;

public interface NoteVersionMapper {
    NoteVersion fromDto(NoteVersionDto version);
    NoteVersionDto toDto(NoteVersion version);
}
