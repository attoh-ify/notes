package com.crowninteractive.notes.dto.message_payload;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CursorPayload {
    private String actorEmail;
    private int position;
    private int length;

    public CursorPayload(String actorEmail, int position, int length) {
        this.actorEmail = actorEmail;
        this.position = position;
        this.length = length;
    }

    public CursorPayload() {}
}
