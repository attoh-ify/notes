package com.example.notes.dto.enqueue;

import com.example.notes.dto.ot.Delta;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Setter
@Getter
public class OperationQueueInPayload {
    private UUID noteId;
    private String opId;
    private int revision;
    private String from;
    private Delta delta;

    public  OperationQueueInPayload(UUID noteId, String opId, int revision, String from, Delta delta) {
        this.noteId = noteId;
        this.opId = opId;
        this.revision = revision;
        this.from = from;
        this.delta = delta;
    }

    public OperationQueueInPayload() {}

    @Override
    public String toString() {
        return "OperationQueueInPayload{" +
                "noteId=" + noteId +
                ", opId='" + opId + '\'' +
                ", revision=" + revision +
                ", from=" + from +
                ", deltaOps=" + (delta != null && delta.ops != null ? delta.ops.size() : 0) +
                '}';
    }
}
