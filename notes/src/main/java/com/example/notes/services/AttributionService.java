package com.example.notes.services;

import com.example.notes.dto.attribution.AttributionBuildResult;
import com.example.notes.dto.attribution.AttributionViewMode;
import com.example.notes.dto.ot.TextOperation;

import java.util.List;
import java.util.UUID;

public interface AttributionService {
    AttributionBuildResult buildReviewProjection(
            String actorEmail,
            UUID noteId,
            List<TextOperation> baseTextOps,
            List<TextOperation> changeTextOps,
            List<TextOperation> mutableRevisionLog,
            AttributionViewMode mode
    );
}
