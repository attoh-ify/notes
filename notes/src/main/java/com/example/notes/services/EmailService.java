package com.example.notes.services;

import com.example.notes.entities.noteAccess.NoteAccessRole;

public interface EmailService {
    void sendRegisterEmail(String actorEmail);
    void sendAccessGrantedEmail(String recipientEmail, String noteTitle, NoteAccessRole role);
    void sendAccessUpdatedEmail(String recipientEmail, String noteTitle, NoteAccessRole newRole);
    void sendAccessDeletedEmail(String recipientEmail, String noteTitle);
}
