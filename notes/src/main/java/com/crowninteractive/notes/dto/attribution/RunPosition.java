package com.crowninteractive.notes.dto.attribution;

// ─── RunPosition ──────────────────────────────────────────────────────────────
//
// Return type of AttributionHelpers.findRunPos().
// Bundles the three values that callers always need together when locating a
// logical position within the run array.
//
//   idx    - index in the runs list of the run that contains the logical position
//   offset - how many characters into that run the logical position falls
//   absPos - the absolute position in visual delta space (includes deleted-text offsets)
// ──────────────────────────────────────────────────────────────────────────────
public record RunPosition (
    int idx,
    int offset,
    int absPos
){
}