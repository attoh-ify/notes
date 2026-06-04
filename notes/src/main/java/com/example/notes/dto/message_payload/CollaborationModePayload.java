package com.example.notes.dto.message_payload;

import com.example.notes.dto.note.CollaborationMode;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class CollaborationModePayload {
    private UUID noteId;
    private CollaborationMode mode;
    private int activeSessionCount;

    public CollaborationModePayload(UUID noteId, CollaborationMode mode, int activeSessionCount) {
        this.noteId = noteId;
        this.mode = mode;
        this.activeSessionCount = activeSessionCount;
    }

    public CollaborationModePayload() {}
}
