package com.crowninteractive.notes.dto.ot;

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

    public TextOperation(String opId, Delta delta, String actorEmail, int revision, OpState state, LocalDateTime createdAt) {
        this.opId = opId;
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
                ", deltaOps=" + (delta != null && delta.ops != null ? delta.ops.size() : 0) +
                ", actorEmail='" + actorEmail + '\'' +
                ", revision=" + revision +
                ", state=" + state +
                ", createdAt=" + createdAt +
                '}';
    }
}
