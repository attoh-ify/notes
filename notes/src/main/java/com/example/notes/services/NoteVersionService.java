package com.example.notes.services;


import com.example.notes.dto.noteVersion.NoteVersionDto;

import java.util.List;
import java.util.UUID;

public interface NoteVersionService {
    List<NoteVersionDto> fetchAllVersions(String actorEmail, UUID noteId);
    NoteVersionDto fetchVersion(String actorEmail, UUID noteId, UUID noteVersionId);
    NoteVersionDto createVersion(String actorEmail, UUID noteId, NoteVersionDto noteVersionDto);
    NoteVersionDto restoreVersion(String actorEmail, UUID noteId, UUID noteVersionId);
}
