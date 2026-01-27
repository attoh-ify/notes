package com.example.notes.feat_document.formatter;

import com.example.notes.shared.model.TextOperation;

public interface DocumentFormatter {
    void applyOperation(TextOperation operation);
    String getText();
}
