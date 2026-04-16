package com.example.notes.dto.attribution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// ─── FormatSuggestionSpan ─────────────────────────────────────────────────────
//
// One contiguous range within a format suggestion's coverage area.
//
// A single format suggestion can have multiple spans because:
//   1. The format may span a paragraph boundary (newline), producing two visual
//      segments in Quill's delta space.
//   2. A format suggestion that originally covered a continuous range may have
//      had a sub-range cancelled, splitting it into two remaining spans.
//
// Coordinates are in "absolute" (visual delta) space — they include positions
// occupied by deleted-text runs because those runs still appear in the Quill
// editor during review.
// ──────────────────────────────────────────────────────────────────────────────
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FormatSuggestionSpan {
    // Absolute position in the visual delta where this span starts
    private int start;
    // Number of characters this span covers
    private int length;
}