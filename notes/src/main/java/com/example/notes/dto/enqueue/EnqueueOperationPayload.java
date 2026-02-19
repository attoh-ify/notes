package com.example.notes.dto.enqueue;

import com.example.notes.dto.ot.Delta;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class EnqueueOperationPayload {
    private Delta delta;
    private int revision;
    private UUID from;

    public EnqueueOperationPayload(Delta delta, int revision, UUID from) {
        this.delta = delta;
        this.revision = revision;
        this.from = from;
    }

    public EnqueueOperationPayload() {}

    @Override
    public String toString() {
        return "EnqueueOperationPayload{" +
                "delta=" + delta +
                ", revision=" + revision +
                ", from='" + from + '\'' +
                '}';
    }
}
