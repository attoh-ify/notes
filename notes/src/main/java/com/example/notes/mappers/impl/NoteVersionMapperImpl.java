package com.example.notes.mappers.impl;

import com.example.notes.dto.noteVersion.NoteVersionDto;
import com.example.notes.entities.noteVersion.NoteVersion;
import com.example.notes.mappers.NoteVersionMapper;
import org.springframework.stereotype.Component;

@Component
public class NoteVersionMapperImpl implements NoteVersionMapper {
    @Override
    public NoteVersion fromDto(NoteVersionDto noteVersionDto) {
        return new NoteVersion(
                noteVersionDto.id(),
                null,
                noteVersionDto.content(),
                noteVersionDto.revision(),
                noteVersionDto.createdBy(),
                noteVersionDto.versionNumber()
        );
    }

    @Override
    public NoteVersionDto toDto(NoteVersion noteVersion) {
        return new NoteVersionDto(
                noteVersion.getId(),
                noteVersion.getContent(),
                noteVersion.getRevision(),
                noteVersion.getCreatedBy(),
                noteVersion.getVersionNumber(),
                noteVersion.getCreatedAt()
        );
    }
}
