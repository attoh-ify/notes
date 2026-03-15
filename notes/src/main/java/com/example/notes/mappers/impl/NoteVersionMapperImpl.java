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
                noteVersionDto.masterDelta(),
                noteVersionDto.revision(),
                noteVersionDto.comment(),
                noteVersionDto.versionNumber()
        );
    }

    @Override
    public NoteVersionDto toDto(NoteVersion noteVersion) {
        return new NoteVersionDto(
                noteVersion.getId(),
                noteVersion.getMasterDelta(),
                noteVersion.getRevision(),
                noteVersion.getComment(),
                noteVersion.getVersionNumber(),
                noteVersion.getCreatedAt()
        );
    }
}
