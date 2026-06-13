package com.crowninteractive.notes.services;

import com.crowninteractive.notes.dto.attribution.AuditProjection;
import com.crowninteractive.notes.dto.noteVersion.CreateNoteVersionPayload;
import com.crowninteractive.notes.dto.noteVersion.NoteVersionDto;

import java.util.List;

public interface NoteVersionService {
    List<NoteVersionDto> fetchAllVersions(String actorEmail, String noteId);
    NoteVersionDto fetchVersion(String actorEmail, String noteId, int noteVersionNumber);
    NoteVersionDto createVersion(String actorEmail, String noteId, CreateNoteVersionPayload payload);
    NoteVersionDto restoreVersion(String actorEmail, String noteId, String noteVersionId);
    AuditProjection auditVersion(String actorEmail, String noteId, String noteVersionId);
}
