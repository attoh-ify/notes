package com.example.notes.shared.formatter;

import com.example.notes.dto.ot.TextOperation;

public interface DocumentFormatter {
    void applyOperation(TextOperation operation);
    String getText();
    void setText(String text);
}
