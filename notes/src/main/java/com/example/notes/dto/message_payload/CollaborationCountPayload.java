package com.example.notes.dto.message_payload;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CollaborationCountPayload {
    private int count;

    public CollaborationCountPayload(int count) {
        this.count = count;
    }

    public CollaborationCountPayload() {}

}
