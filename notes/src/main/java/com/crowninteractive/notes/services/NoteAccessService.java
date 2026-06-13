package com.crowninteractive.notes.services;

import com.crowninteractive.notes.dto.noteAccess.NoteAccessDto;
import com.crowninteractive.notes.dto.noteAccess.NoteAccessPayload;

import java.util.List;

public interface NoteAccessService {
    NoteAccessDto addAccess(String userEmail, String noteId, NoteAccessPayload noteAccess);
    NoteAccessDto updateAccess(String userEmail, String noteId, String noteAccessId, NoteAccessPayload noteAccess);
    void  deleteAccess(String userEmail, String noteId, String noteAccessId);
    List<NoteAccessDto> getAllAccess(String userEmail, String noteId);
}
