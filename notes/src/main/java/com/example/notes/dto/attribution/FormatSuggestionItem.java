package com.example.notes.dto.attribution;

import com.example.notes.dto.note.OpReference;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

// ─── FormatSuggestionItem ─────────────────────────────────────────────────────
//
// Represents one format suggestion group — an actor applied (or removed) one or
// more formatting attributes (bold, italic, color, etc.) over a range of text.
// The reviewer can accept (keep the formatting) or reject (remove it).
//
// Key design decisions:
//
//   attributes (String JSON):
//     Stored as a serialized JSON string (e.g. '{"bold":true}') so it can be
//     compared by value as a Map key when grouping adjacent format ops with
//     identical attributes. Parsed only when the attributes need to be applied.
//
//   spans (List<FormatSuggestionSpan>):
//     Multiple spans because formatting can span paragraph boundaries or have
//     sub-ranges cancelled, leaving non-contiguous coverage areas.
//
//   dependsOnInsertGroupIds (List<String>):
//     If actor A inserted text and actor B formatted it, the format suggestion
//     "depends on" A's insert. You must resolve the insert (accept/reject) before
//     you can act on the format. This list tracks those dependencies.
//
//   previewText (String):
//     A short (~60 char) excerpt of the formatted text, shown in the sidebar so
//     the reviewer can identify which text is being formatted at a glance.
// ──────────────────────────────────────────────────────────────────────────────
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FormatSuggestionItem {
    private String groupId;
    private String actorEmail;
    private String createdAt;
    private String attributes;
    @Builder.Default
    private List<OpReference> references = new ArrayList<>();
    // One or more contiguous ranges within the visual delta space
    @Builder.Default
    private List<FormatSuggestionSpan> spans = new ArrayList<>();
    // Short excerpt of the formatted text for sidebar display (max 60 chars)
    @Builder.Default
    private String previewText = "";
    // Insert group IDs that must be resolved before this format can be accepted/rejected
    @Builder.Default
    private List<String> dependsOnInsertGroupIds = new ArrayList<>();
}