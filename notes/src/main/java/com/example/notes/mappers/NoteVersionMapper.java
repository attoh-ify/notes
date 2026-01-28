package com.example.notes.mappers;

import com.example.notes.dto.noteVersion.NoteVersionDto;
import com.example.notes.entities.noteVersion.NoteVersion;

public interface NoteVersionMapper {
    NoteVersion fromDto(NoteVersionDto version);
    NoteVersionDto toDto(NoteVersion version);
}
