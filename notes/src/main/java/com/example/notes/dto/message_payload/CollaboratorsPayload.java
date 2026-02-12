package com.example.notes.dto.message_payload;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class CollaboratorsPayload {
    private List<String> collaborators;

    public CollaboratorsPayload(List<String> collaborators) {
        this.collaborators = collaborators;
    }

    public CollaboratorsPayload() {}
}