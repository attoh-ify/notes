package com.example.notes.dto.message_payload;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CursorPayload {
    private String actorEmail;
    private int position;

    public CursorPayload(String actorEmail, int position) {
        this.actorEmail = actorEmail;
        this.position = position;
    }

    public CursorPayload() {}
}
