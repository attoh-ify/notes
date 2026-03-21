package com.example.notes.dto.note;

import java.util.List;

public record CancelFormatPayload (
        List<String> cancelledOpIds,
        String cancellingOpId,
        int opLength,
        int totalLength,
        int consumedBefore
) {
}
