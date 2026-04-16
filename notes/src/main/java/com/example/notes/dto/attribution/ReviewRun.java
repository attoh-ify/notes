package com.example.notes.dto.attribution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

// ─── ReviewRun ────────────────────────────────────────────────────────────────
//
// The atomic unit of the attribution pipeline.
//
// The committed document is broken into runs at the start of buildReviewProjection(),
// and then each pending op mutates the run array (splitting, inserting, marking
// deletions, applying format attrs). At the end of the pipeline the runs are
// collapsed into a Quill Delta for rendering.
//
// Two attribute maps are kept separate:
//
//   baseAttributes (Map<String, Object>):
//     The formatting that is "committed" — comes from the committed document or
//     is applied/restored during format-cancellation handling. This is what the
//     text looks like without any pending suggestions.
//
//   suggestionAttributes (Map<String, Object>):
//     Formatting applied by a pending format-retain op. Kept separate from
//     baseAttributes so we can independently show/hide the format suggestion
//     overlay without recomputing the whole projection.
//
// logicalStart:
//   Position in the logical document (i.e., skipping deleted-text runs).
//   Used to map between Quill's absolute delta positions and the document positions
//   that pending op deltas refer to.
//
// insertSuggestion / deleteSuggestion:
//   Present when this run is part of a pending insert or delete suggestion.
//   Null for plain committed runs.
// ──────────────────────────────────────────────────────────────────────────────
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReviewRun {

    // Raw text content of this run
    private String text;

    // Committed formatting attributes (bold, italic, color, etc.)
    @Builder.Default
    private Map<String, Object> baseAttributes = new HashMap<>();

    // Pending format suggestion attributes (separate to allow overlay toggling)
    @Builder.Default
    private Map<String, Object> suggestionAttributes = new HashMap<>();

    // Position of this run in logical (non-deleted) document space
    private int logicalStart;

    // Which pending op contributed this run (empty string for committed runs)
    private String opId;

    // Which component index within that op's delta produced this run
    private int insertComponentIndex;

    // Non-null when this run is part of an insert suggestion (pending, not yet accepted)
    private InsertSuggestion insertSuggestion;

    // Non-null when this run is part of a delete suggestion (still visible in review)
    private DeleteSuggestion deleteSuggestion;
}
