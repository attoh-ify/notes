package com.example.notes.dto.note;

import java.util.List;

public record OpReferenceResponse(
    String opId,
    List<Integer> componentIndexes
) {}
