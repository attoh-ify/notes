package com.crowninteractive.notes.services;

import com.crowninteractive.notes.dto.attribution.AttributionBuildResult;
import com.crowninteractive.notes.dto.attribution.AttributionViewMode;
import com.crowninteractive.notes.dto.ot.TextOperation;

import java.util.List;

public interface AttributionService {
    AttributionBuildResult buildReviewProjection(
            String actorEmail,
            String noteId,
            List<TextOperation> baseTextOps,
            List<TextOperation> changeTextOps,
            List<TextOperation> mutableRevisionLog,
            AttributionViewMode mode
    );
}
