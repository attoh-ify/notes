package com.crowninteractive.notes.dto.message_payload;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReviewInProgressResponsePayload {
    private String noteId;
    private boolean state;

    public ReviewInProgressResponsePayload(String noteId, boolean state) {
        this.noteId = noteId;
        this.state = state;
    }

    public ReviewInProgressResponsePayload() {
    }
}
