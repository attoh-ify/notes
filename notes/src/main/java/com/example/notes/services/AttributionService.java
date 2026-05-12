package com.example.notes.services;

import com.example.notes.dto.attribution.ReviewProjection;
import com.example.notes.dto.ot.TextOperation;

import java.util.List;
import java.util.UUID;

public interface AttributionService {
    ReviewProjection buildReviewProjection(String actorEmail, UUID noteId, List<TextOperation> baseTextOps, List<TextOperation> changeTextOps);
}
