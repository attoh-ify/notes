package com.example.notes.dto.attribution;

import com.example.notes.dto.ot.Delta;

import java.util.List;

// ─── ReviewProjection ─────────────────────────────────────────────────────────
//
// The final output of AttributionServiceImpl.buildReviewProjection().
//
// visualDelta:
//   A Quill-compatible delta (list of insert ops with attributes) ready to be
//   applied to the Quill editor via setContents(). Encodes all suggestion metadata
//   as special Quill attributes ("suggestion-insert", "suggestion-delete", etc.)
//   that registered Quill blots render as highlights/strikethroughs.
//
// formatSuggestions:
//   The list of format suggestion groups. Sent to the frontend alongside visualDelta
//   so the sidebar panel can display them and the accept/reject handlers can act on them.
// ──────────────────────────────────────────────────────────────────────────────
public record ReviewProjection (
    // Quill delta ready for quill.setContents() on the frontend
    Delta visualDelta,
    // All format suggestion groups generated during this projection build
    List<FormatSuggestionItem> formatSuggestions
){
}