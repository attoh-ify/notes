package com.example.notes.dto.message_payload;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class SoloSyncAckPayload {
    private UUID noteId;
    private String opId;
    private boolean success;
    private Integer revision;
    private String error;

    public SoloSyncAckPayload(UUID noteId, String opId, boolean success, Integer revision, String error) {
        this.noteId = noteId;
        this.opId = opId;
        this.success = success;
        this.revision = revision;
        this.error = error;
    }

    public SoloSyncAckPayload() {}
}