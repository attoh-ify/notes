package com.example.notes.feat_document.models;

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
