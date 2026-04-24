package com.example.notes.dto.attribution;

import com.example.notes.dto.ot.Delta;

import java.util.List;

// ─── ReviewProjection ─────────────────────────────────────────────────────────
//
// The result of buildReviewProjection().
//
// baseDelta:
//   The plain document expressed as a Quill insert-only delta.
//   The frontend calls quill.setContents(baseDelta) first to load the
//   base document with its real formatting. No suggestion metadata
//   is present here.
//
// visualDelta:
//   A retain-based delta that the frontend applies on top of baseDelta via
//   quill.updateContents(visualDelta). Using retains instead of inserts means
//   suggestion attrs are scoped precisely to the runs they belong to and cannot
//   bleed into surrounding committed text through Quill's inline attr inheritance.
//
//   Each retain op may carry:
//     - base-attributes        : the run's committed formatting snapshot (Map)
//     - suggestion-attributes  : pending format attrs as a map (for format suggestions)
//     - suggestion-insert      : insert suggestion metadata
//     - suggestion-delete      : delete suggestion metadata
//     - suggestion-delete-newline : delete suggestion metadata for a newline run
//
//   Pending insert runs that don't exist in the committed document are still
//   emitted as insert ops (retain is impossible for absent text).
//
//   Deleted committed runs are emitted as insert("↵"/"text") ops with
//   suggestion-delete metadata attached.
//
// formatSuggestions:
//   All active format suggestion groups, for the sidebar review panel.
// ──────────────────────────────────────────────────────────────────────────────
public record ReviewProjection(
        Delta baseDelta,
        Delta visualDelta,
        List<FormatSuggestionItem> formatSuggestions
) {}