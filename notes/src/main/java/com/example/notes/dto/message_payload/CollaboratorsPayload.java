package com.example.notes.dto.message_payload;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class CollaboratorsPayload {
    private Map<Object, Object> collaborators;

    public CollaboratorsPayload(Map<Object, Object> collaborators) {
        this.collaborators = collaborators;
    }

    public CollaboratorsPayload() {}
}