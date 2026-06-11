package com.example.notes.services;

import com.example.notes.dto.attribution.AuditProjection;
import com.example.notes.dto.noteVersion.CreateNoteVersionPayload;
import com.example.notes.dto.noteVersion.NoteVersionDto;

import java.util.List;
import java.util.UUID;

public interface NoteVersionService {
    List<NoteVersionDto> fetchAllVersions(String actorEmail, UUID noteId);
    NoteVersionDto fetchVersion(String actorEmail, UUID noteId, int noteVersionNumber);
    NoteVersionDto createVersion(String actorEmail, UUID noteId, CreateNoteVersionPayload payload);
    NoteVersionDto restoreVersion(String actorEmail, UUID noteId, UUID noteVersionId);
    AuditProjection auditVersion(String actorEmail, UUID noteId, UUID versionId);
}
