package com.crowninteractive.notes.dto.message_payload;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SoloSyncAckPayload {
    private String noteId;
    private String opId;
    private boolean success;
    private Integer revision;
    private String error;

    public SoloSyncAckPayload(String noteId, String opId, boolean success, Integer revision, String error) {
        this.noteId = noteId;
        this.opId = opId;
        this.success = success;
        this.revision = revision;
        this.error = error;
    }

    public SoloSyncAckPayload() {}
}