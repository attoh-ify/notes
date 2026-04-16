package com.example.notes.dto.attribution;

import com.example.notes.dto.note.OpReference;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

// ─── InsertSuggestion ─────────────────────────────────────────────────────────
//
// Represents one insert suggestion group — a contiguous block of text inserted
// by one actor that is still pending review.
//
// Multiple consecutive insert operations by the same actor are merged into one
// group so the reviewer sees "UserA inserted: Hello World" rather than one
// entry per keystroke. Grouping is done by checking adjacent runs in the pipeline.
//
// Fields:
//   groupId    - unique ID for this group within one projection build (e.g. "g_1")
//   actorEmail - the user who made the insertion
//   createdAt  - ISO-8601 timestamp; updated to the most recent value when merging
//   references - all op+component pairs that contributed text to this group
//   startIndex - logical document position where this group begins
// ──────────────────────────────────────────────────────────────────────────────
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class InsertSuggestion {
    private String groupId;
    private String actorEmail;
    private String createdAt;
    @Builder.Default
    private List<OpReference> references = new ArrayList<>();
    private int startIndex;
}
