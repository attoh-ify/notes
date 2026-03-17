package com.example.notes.dto.ot;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Setter
@Getter
public class TextOperation {
    private String opId;
    private Delta delta;
    private String actorEmail;
    private int revision;
    private OpState state;
    private LocalDateTime createdAt;

    public TextOperation() {}

    public TextOperation(Delta delta, String actorEmail, int revision, OpState state, LocalDateTime createdAt) {
        this.opId = UUID.randomUUID().toString();
        this.delta = delta;
        this.actorEmail = actorEmail;
        this.revision = revision;
        this.state = state;
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "TextOperation{" +
                "opId='" + opId + '\'' +
                ", delta=" + delta +
                ", actorEmail='" + actorEmail + '\'' +
                ", revision=" + revision +
                ", state=" + state +
                ", createdAt=" + createdAt +
                '}';
    }
}
