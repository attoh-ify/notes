package com.example.notes.services;

import com.example.notes.dto.noteAccess.NoteAccessDto;
import com.example.notes.dto.noteAccess.NoteAccessPayload;

import java.util.List;
import java.util.UUID;

public interface NoteAccessService {
    NoteAccessDto addAccess(String userEmail, UUID noteId, NoteAccessPayload noteAccess);
    NoteAccessDto updateAccess(String userEmail, UUID noteId, UUID noteAccessId, NoteAccessPayload noteAccess);
    void  deleteAccess(String userEmail, UUID noteId, UUID noteAccessId);
    List<NoteAccessDto> getAllAccess(String userEmail, UUID noteId);
}
