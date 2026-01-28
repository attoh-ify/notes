package com.example.notes.shared.operation_transformations;

import com.example.notes.dto.ot.TextOperation;

public interface OperationTransformations {
    TextOperation[] transform(TextOperation op1, TextOperation op2);
}
