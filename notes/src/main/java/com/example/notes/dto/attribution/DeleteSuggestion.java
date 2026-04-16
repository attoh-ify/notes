package com.example.notes.dto.attribution;

import com.example.notes.dto.note.OpReference;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

// ─── DeleteSuggestion ─────────────────────────────────────────────────────────
//
// Represents one delete suggestion group — text that was deleted by an actor
// but is kept visible in review mode (rendered as strikethrough).
// The reviewer can accept (remove the text) or reject (restore it).
//
// Analogous to InsertSuggestion — consecutive deletes by the same actor are
// merged into one group for a cleaner review UX.
// ──────────────────────────────────────────────────────────────────────────────
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeleteSuggestion {
    private String groupId;
    private String actorEmail;
    private String createdAt;
    @Builder.Default
    private List<OpReference> references = new ArrayList<>();
}