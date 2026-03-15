package com.example.notes.dto.enqueue;

import com.example.notes.dto.ot.Delta;
import com.example.notes.dto.ot.TextOperation;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Setter
@Getter
public class OperationQueueInPayload {
    private UUID noteId;
    private int revision;
    private String from;
    private Delta delta;

    public  OperationQueueInPayload(UUID noteId, int revision, String from, Delta delta) {
        this.noteId = noteId;
        this.revision = revision;
        this.from = from;
        this.delta = delta;
    }

    public OperationQueueInPayload() {}

    @Override
    public String toString() {
        return "OperationQueueInPayload{" +
                "noteId=" + noteId +
                ", revision=" + revision +
                ", from=" + from +
                ", delta=" + delta +
                '}';
    }
}
