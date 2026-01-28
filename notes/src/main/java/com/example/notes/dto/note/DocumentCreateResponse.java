package com.example.notes.dto.note;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class DocumentCreateResponse {
    private String docId;

    public DocumentCreateResponse(String docId) {
        this.docId = docId;
    }

}
