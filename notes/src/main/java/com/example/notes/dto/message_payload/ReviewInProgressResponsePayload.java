package com.example.notes.dto.message_payload;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class ReviewInProgressResponsePayload {
    private UUID noteId;
    private boolean state;

    public ReviewInProgressResponsePayload(UUID noteId, boolean state) {
        this.noteId = noteId;
        this.state = state;
    }

    public ReviewInProgressResponsePayload() {
    }
}
