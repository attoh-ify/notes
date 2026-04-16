package com.example.notes.dto.note;

import java.util.List;

public record CancelFormatPayload (
        List<OpReference> targetReferences,
        String cancellingOpId,
        int retainComponentIndex,
        int opLength,
        int consumedBefore
) {
}
