package com.example.notes.services;

import com.example.notes.dto.attribution.ReviewProjection;

import java.util.UUID;

public interface AttributionService {
    ReviewProjection buildReviewProjection(String actorEmail, UUID noteId);
}
