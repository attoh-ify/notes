package com.example.notes.dto.attribution;

import com.example.notes.dto.note.OpReference;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

// ─── PendingFormatCancellation ─────────────────────────────────────────────────
//
// Tracks a format cancellation that needs to be persisted.
//
// When a pending format-retain op "undoes" a previous format suggestion
// (e.g. the incoming retain restores the pre-suggestion base state),
// we don't immediately call the split API — we accumulate these records and
// flush them at the end of buildReviewProjection().
//
// Fields:
//   groupId                - the format suggestion group being cancelled
//   references             - op references that defined the original format
//   cancellingOpId         - the op whose retain component is cancelling the format
//   retainComponentIndex   - which component index inside that op's delta is responsible
//   consumedBefore         - chars already consumed by this retain before the cancellation range
//   length                 - length of the cancellation range (may grow if contiguous runs are cancelled)
// ──────────────────────────────────────────────────────────────────────────────
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PendingFormatCancellation {
    private String groupId;
    @Builder.Default
    private List<OpReference> references = new ArrayList<>();
    private String cancellingOpId;
    private int retainComponentIndex;
    private int consumedBefore;
    private int length;
}
