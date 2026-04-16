package com.example.notes.dto.note;

import java.util.List;

// ─── OpReferenceResponse ──────────────────────────────────────────────────────
//
// The shape sent to the backend when submitting accepted references.
// Multiple OpReference entries sharing the same opId are collapsed into one
// OpReferenceResponse so the backend receives a per-opId list of component
// indexes rather than flat pairs. This is the format the review save API expects.
// ──────────────────────────────────────────────────────────────────────────────
public record OpReferenceResponse(
    String opId,
    List<Integer> componentIndexes
) {}
