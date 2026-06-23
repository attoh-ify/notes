package com.crowninteractive.notes.mappers.impl;

import com.crowninteractive.notes.dto.noteVersion.NoteVersionDto;
import com.crowninteractive.notes.entities.noteVersion.NoteVersion;
import com.crowninteractive.notes.mappers.NoteVersionMapper;
import org.springframework.stereotype.Component;

@Component
public class NoteVersionMapperImpl implements NoteVersionMapper {
    @Override
    public NoteVersion fromDto(NoteVersionDto noteVersionDto) {
        return new NoteVersion(
                noteVersionDto.getId(),
                noteVersionDto.getNoteVersionId(),
                null,
                noteVersionDto.getMasterDelta(),
                noteVersionDto.getRevision(),
                noteVersionDto.getComment(),
                noteVersionDto.getVersionNumber()
        );
    }

    @Override
    public NoteVersionDto toDto(NoteVersion noteVersion) {
        return new NoteVersionDto(
                noteVersion.getId(),
                noteVersion.getNoteVersionId(),
                noteVersion.getMasterDelta(),
                noteVersion.getRevision(),
                noteVersion.getComment(),
                noteVersion.getVersionNumber(),
                noteVersion.getCreatedAt()
        );
    }
}
