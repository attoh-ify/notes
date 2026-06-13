package com.crowninteractive.notes.dto.message_payload;

import com.crowninteractive.notes.dto.note.CollaborationMode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CollaborationModePayload {
    private String noteId;
    private CollaborationMode mode;
    private int activeSessionCount;

    public CollaborationModePayload(String noteId, CollaborationMode mode, int activeSessionCount) {
        this.noteId = noteId;
        this.mode = mode;
        this.activeSessionCount = activeSessionCount;
    }

    public CollaborationModePayload() {}
}
