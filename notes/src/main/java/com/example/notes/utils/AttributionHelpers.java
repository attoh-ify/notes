package com.example.notes.utils;

import com.example.notes.dto.attribution.*;
import com.example.notes.dto.note.OpReference;
import com.example.notes.dto.ot.Delta;
import com.example.notes.dto.ot.Op;
import com.example.notes.dto.ot.OpState;
import com.example.notes.dto.ot.TextOperation;
import com.example.notes.exceptions.BadRequestException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

// ─── AttributionHelpers.java ──────────────────────────────────────────────────
//
// Pure, stateless helper methods for the attribution pipeline.
//
// @UtilityClass (Lombok) makes the class final with a private constructor and
// makes all methods static.
// ──────────────────────────────────────────────────────────────────────────────

@Slf4j
@UtilityClass
public class AttributionHelpers {
    // Shared ObjectMapper for JSON serialisation/deserialisation.
    // Used to parse `attributes` strings (e.g. '{"bold":true}') into Maps and
    // to serialise attribute Maps back into canonical JSON strings for comparison.
    static final ObjectMapper MAPPER = new ObjectMapper();

    // ─── Group ID counter ─────────────────────────────────────────────────────
    //
    // Simple incrementing counter for generating unique group IDs within one
    // projection build. Reset to 0 at the start of each buildReviewProjection()
    // call so IDs are predictable (g_1, g_2, ...) for debugging.
    //
    // NOTE: Not thread-safe by design — each projection build runs on a single
    // thread. If you add concurrency, wrap this in an AtomicInteger or pass it
    // as a parameter instead.
    // ─────────────────────────────────────────────────────────────────────────
    private static int groupCtr = 0;

    public static void resetGroupCounter() {
        groupCtr = 0;
    }

    public static String nextId() {
        return "g_" + (++groupCtr);
    }

    // ─── attrsEq ──────────────────────────────────────────────────────────────
    //
    // Deep equality check for attribute maps using canonical JSON serialisation.
    // Two maps are considered equal if their JSON representations are identical.
    //
    // Used by buildVisualDelta() to decide whether two adjacent runs can be merged
    // into a single Quill delta op (they can only merge if their effective
    // attributes are identical).
    // ─────────────────────────────────────────────────────────────────────────
    public static boolean attrsEq(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Object> aa = (a != null) ? a : Collections.emptyMap();
        Map<String, Object> bb = (b != null) ? b : Collections.emptyMap();
        try {
            // Sort keys to ensure canonical representation before comparing
            return MAPPER.writeValueAsString(new TreeMap<>(aa))
                    .equals(MAPPER.writeValueAsString(new TreeMap<>(bb)));
        } catch (Exception e) {
            return aa.equals(bb);
        }
    }

    // ─── intersectAttrs ───────────────────────────────────────────────────────
    //
    // Returns only the key-value pairs that exist in BOTH maps with equal values.
    //
    // Used when detecting "inherited attributes" during insert processing:
    // if actor B inserts bold text immediately after actor A's bold insert, the
    // bold comes from A's formatting context rather than B's own choice.
    // The intersection of B's ownAttrs and A's run attributes tells us which
    // formatting B "inherited" and should be attributed to A.
    // ─────────────────────────────────────────────────────────────────────────
    public static Map<String, Object> intersectAttrs(
            Map<String, Object> candidate,
            Map<String, Object> reference
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (candidate == null || reference == null) return out;
        for (Map.Entry<String, Object> entry : candidate.entrySet()) {
            Object refVal = reference.get(entry.getKey());
            if (refVal != null && Objects.equals(refVal, entry.getValue())) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    // ─── subtractAttrs ────────────────────────────────────────────────────────
    //
    // Returns a copy of `attrs` with all keys that appear in `remove` deleted.
    //
    // Used after stripping inherited attributes from runs — once a set of attrs
    // has been "claimed" by a format suggestion, they are removed from the run's
    // baseAttributes/suggestionAttributes so they don't appear doubled.
    // ─────────────────────────────────────────────────────────────────────────
    public static Map<String, Object> subtractAttrs(
            Map<String, Object> attrs,
            Map<String, Object> remove
    ) {
        Map<String, Object> out = new LinkedHashMap<>(attrs != null ? attrs : Collections.emptyMap());
        if (remove != null) {
            remove.keySet().forEach(out::remove);
        }
        return out;
    }

    // ─── stripNullAttrs ───────────────────────────────────────────────────────
    //
    // Returns a copy of `attrs` with all null-valued keys removed.
    //
    // In Quill's delta format, null means "remove this attribute". When building
    // a format suggestion's attribute string, we only want the keys that are
    // actually being ADDED (non-null), not the removal instructions.
    // ─────────────────────────────────────────────────────────────────────────
    public static Map<String, Object> stripNullAttrs(Map<String, Object> attrs) {
        if (attrs == null) return Collections.emptyMap();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : attrs.entrySet()) {
            if (entry.getValue() != null) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    public static Map<String, Object> overlayAttrsPreserveNull(
            Map<String, Object> baseAttrs,
            Map<String, Object> deltaAttrs
    ) {
        Map<String, Object> out = new LinkedHashMap<>(
                baseAttrs != null ? baseAttrs : Collections.emptyMap()
        );
        if (deltaAttrs != null) {
            out.putAll(deltaAttrs);
        }
        return out;
    }

    public static Map<String, Object> applyAttrsForEffectiveView(
            Map<String, Object> baseAttrs,
            Map<String, Object> deltaAttrs
    ) {
        Map<String, Object> out = new LinkedHashMap<>(
                baseAttrs != null ? baseAttrs : Collections.emptyMap()
        );
        out.putAll(deltaAttrs);

        return out;
    }

    // ─── getEffectiveAttrs ────────────────────────────────────────────────────
    //
    // Returns the combined ("effective") attribute map for a run by merging
    // baseAttributes and suggestionAttributes, with suggestionAttributes winning
    // on key conflicts.
    //
    // We keep the two maps separate throughout the pipeline so we can independently
    // show/hide the format suggestion overlay. This helper is used wherever code
    // needs the full visual attribute set (e.g. inherited-attr detection during
    // insert processing).
    // ─────────────────────────────────────────────────────────────────────────
    public static Map<String, Object> getEffectiveAttrs(ReviewRun run) {
        if (run == null) return Collections.emptyMap();
        return applyAttrsForEffectiveView(
                run.getBaseAttributes(),
                run.getSuggestionAttributes()
        );
    }

    // ─── parseAttrs ───────────────────────────────────────────────────────────
    //
    // Parses a JSON attribute string (e.g. '{"bold":true}') into a Map.
    // Returns an empty map if parsing fails so callers don't need null checks.
    //
    // Used wherever we need to work with the actual attribute values inside a
    // FormatSuggestionItem (the `attributes` field is stored as a JSON string).
    // ─────────────────────────────────────────────────────────────────────────
    public static Map<String, Object> parseAttrs(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[PARSE_ATTRS] Failed to parse attribute JSON: {} — returning empty map", json);
            return Collections.emptyMap();
        }
    }

    // ─── attrsToJson ──────────────────────────────────────────────────────────
    //
    // Serialises an attribute map to a canonical JSON string with sorted keys.
    // Sorted keys ensure that two attribute maps with the same contents always
    // produce the same string, which is required for equality checks used when
    // grouping adjacent format operations.
    // ─────────────────────────────────────────────────────────────────────────
    public static String attrsToJson(Map<String, Object> attrs) {
        if (attrs == null || attrs.isEmpty()) return "{}";
        try {
            return MAPPER.writeValueAsString(new TreeMap<>(attrs));
        } catch (Exception e) {
            log.warn("[ATTRS_TO_JSON] Serialisation failed — returning {{}}");
            return "{}";
        }
    }

    // ─── mergeUniqueRefs ──────────────────────────────────────────────────────
    //
    // Merges two lists of OpReferences, deduplicating by (opId, componentIndex).
    //
    // Used when collapsing adjacent runs into one in buildVisualDelta() — the
    // merged run needs to carry all op references from both constituent runs so
    // the backend receives complete attribution information on accept/reject.
    // ─────────────────────────────────────────────────────────────────────────
    public static List<OpReference> mergeUniqueRefs(
            List<OpReference> a,
            List<OpReference> b
    ) {
        Map<String, OpReference> seen = new LinkedHashMap<>();
        List<OpReference> aList = (a != null) ? a : Collections.emptyList();
        List<OpReference> bList = (b != null) ? b : Collections.emptyList();
        for (OpReference ref : aList) {
            seen.put(ref.opId() + ":" + ref.componentIndex(), ref);
        }
        for (OpReference ref : bList) {
            seen.putIfAbsent(ref.opId() + ":" + ref.componentIndex(), ref);
        }
        return new ArrayList<>(seen.values());
    }

    public static List<FormatKeyDecision> classifyFormatKeyChanges(
            Map<String, Object> baseAttrs,
            Map<String, Object> suggestionAttrs,
            Map<String, Object> incomingAttrs
    ) {
        List<FormatKeyDecision> decisions = new ArrayList<>();
        if (incomingAttrs == null || incomingAttrs.isEmpty()) return decisions;

        Map<String, Object> base = baseAttrs != null ? baseAttrs : Collections.emptyMap();
        Map<String, Object> suggested = suggestionAttrs != null ? suggestionAttrs : Collections.emptyMap();

        for (String key : incomingAttrs.keySet()) {
            Object baseValue = base.get(key);
            boolean hasSuggestedKey = suggested.containsKey(key);
            Object currentSuggestedValue = hasSuggestedKey ? suggested.get(key) : baseValue;
            Object incomingValue = incomingAttrs.get(key);

            FormatKeyChangeType type;

            if (Objects.equals(incomingValue, currentSuggestedValue)) {
                type = FormatKeyChangeType.NO_OP;
            } else if (Objects.equals(incomingValue, baseValue)) {
                type = FormatKeyChangeType.CANCEL;
            } else if (hasSuggestedKey) {
                type = FormatKeyChangeType.REPLACE;
            } else {
                type = FormatKeyChangeType.NEW_SUGGESTION;
            }

            decisions.add(new FormatKeyDecision(
                    key,
                    baseValue,
                    currentSuggestedValue,
                    incomingValue,
                    type
            ));
        }

        return decisions;
    }

    // ─── findRunPos ───────────────────────────────────────────────────────────
    //
    // Finds which run in the list contains the given logical position, and where
    // within that run the position falls.
    //
    // "Logical position" means position in the document as if deleted text does
    // not exist. Runs marked with a deleteSuggestion are skipped when counting
    // logical positions because deleted text doesn't occupy real document space —
    // but we still advance absPos for them because they appear in the visual delta.
    //
    // Returns a RunPosition with:
    //   idx    - the run's index in the list
    //   offset - how many characters into that run the logical position falls
    //   absPos - absolute visual-delta position (includes deleted-text offsets)
    //
    // If logicalPos is past all non-deleted runs, returns a sentinel pointing
    // just past the end of the list (idx = runs.size()).
    // ─────────────────────────────────────────────────────────────────────────
    public static RunPosition findRunPos(List<ReviewRun> runs, int logicalPos) {
        int pos = 0;
        int absPos = 0;

        for (int i = 0; i < runs.size(); i++) {
            ReviewRun r = runs.get(i);

            if (r.getDeleteSuggestion() != null) {
                // Deleted runs occupy visual space but not logical space
                absPos += r.getText().length();
                continue;
            }

            if (pos == logicalPos) {
                return new RunPosition(i, 0, absPos);
            }

            if (pos + r.getText().length() > logicalPos) {
                int off = logicalPos - pos;
                return new RunPosition(i, off, absPos + off);
            }

            pos += r.getText().length();
            absPos += r.getText().length();
        }

        // Past the end of all runs
        return new RunPosition(runs.size(), 0, absPos);
    }

    // ─── splitAt ──────────────────────────────────────────────────────────────
    //
    // Splits the run at index `idx` at the given character `offset`, producing
    // two runs in its place. Returns the index of the SECOND run (the right half).
    //
    // Why we need this: pending ops may target part of a run (e.g. a format retain
    // that starts in the middle of a word). Before applying the op, we split the
    // run so each resulting part can be independently attributed.
    //
    // Example: run = "Hello World", offset = 5
    //   → runs[idx]   = "Hello"   (logicalStart unchanged)
    //   → runs[idx+1] = " World"  (logicalStart += 5)
    //
    // No-op (returns idx) if offset is 0, >= run.length, or idx is out of bounds.
    // ─────────────────────────────────────────────────────────────────────────
    public static int splitAt(List<ReviewRun> runs, int idx, int offset) {
        if (idx >= runs.size() || offset <= 0 || offset >= runs.get(idx).getText().length()) {
            return idx;
        }

        ReviewRun r = runs.get(idx);
        InsertSliceSplit insertSliceSplit = splitInsertSlices(
                r.getInsertReferences() != null ? r.getInsertReferences() : Collections.emptyList(),
                offset
        );

        // Left half — keeps the original logicalStart
        ReviewRun left = ReviewRun.builder()
                .text(r.getText().substring(0, offset))
                .baseAttributes(new LinkedHashMap<>(r.getBaseAttributes() != null ? r.getBaseAttributes() : Collections.emptyMap()))
                .suggestionAttributes(new LinkedHashMap<>(r.getSuggestionAttributes() != null ? r.getSuggestionAttributes() : Collections.emptyMap()))
                .logicalStart(r.getLogicalStart())
                .insertReferences(insertSliceSplit.left())
                .insertSuggestion(r.getInsertSuggestion() != null ? copyInsertSuggestion(r.getInsertSuggestion()) : null)
                .deleteSuggestion(r.getDeleteSuggestion() != null ? copyDeleteSuggestion(r.getDeleteSuggestion()) : null)
                .build();

        // Right half — logicalStart shifted by offset
        ReviewRun right = ReviewRun.builder()
                .text(r.getText().substring(offset))
                .baseAttributes(new LinkedHashMap<>(r.getBaseAttributes() != null ? r.getBaseAttributes() : Collections.emptyMap()))
                .suggestionAttributes(new LinkedHashMap<>(r.getSuggestionAttributes() != null ? r.getSuggestionAttributes() : Collections.emptyMap()))
                .logicalStart(r.getLogicalStart() + offset)
                .insertReferences(insertSliceSplit.right())
                .insertSuggestion(r.getInsertSuggestion() != null ? copyInsertSuggestion(r.getInsertSuggestion()) : null)
                .deleteSuggestion(r.getDeleteSuggestion() != null ? copyDeleteSuggestion(r.getDeleteSuggestion()) : null)
                .build();

        runs.set(idx, left);
        runs.add(idx + 1, right);

        return idx + 1;
    }

    // ─── isOnlyNewlineRetain ──────────────────────────────────────────────────
    //
    // Returns true if the logical range [logicalStart, logicalStart + retainLength]
    // consists entirely of "\n" characters.
    //
    // This is the key "bridge" condition for multi-line format suggestions. In Quill,
    // paragraph-level formatting is stored on the newline character. A plain retain
    // over just a newline between two formatted text segments means the format
    // suggestion should span across that newline as one group rather than splitting
    // into two groups.
    //
    // Example: bold "Hello\nWorld" → the retain sequence is:
    //   [format-retain "Hello"][plain-retain "\n"][format-retain "World"]
    // The plain-retain is newline-only → bridge condition holds → one format group.
    // ─────────────────────────────────────────────────────────────────────────
    public static boolean isOnlyNewlineRetain(
            List<ReviewRun> runs,
            int logicalStart,
            int retainLength
    ) {
        RunPosition pos = findRunPos(runs, logicalStart);
        int runIdx = pos.idx();
        int offset = pos.offset();
        int remaining = retainLength;
        boolean sawOverlap = false;

        for (int i = runIdx; i < runs.size() && remaining > 0; i++) {
            ReviewRun run = runs.get(i);
            if (run.getDeleteSuggestion() != null) continue;

            sawOverlap = true;
            int lenToCheck = Math.min(run.getText().length() - offset, remaining);

            for (int j = offset; j < offset + lenToCheck; j++) {
                if (run.getText().charAt(j) != '\n') return false;
            }

            remaining -= lenToCheck;
            offset = 0;
        }

        return sawOverlap;
    }

    // ─── findAdjacentSpanIndex ────────────────────────────────────────────────
    //
    // Returns the index of the span whose END equals `spanStart` (i.e., the span
    // that is immediately adjacent on the left to a new span starting at spanStart).
    //
    // Used in format group span extension: instead of always pushing a new span,
    // we extend the adjacent one if it exists, keeping the spans list compact.
    //
    // Returns -1 if no adjacent span is found.
    // ─────────────────────────────────────────────────────────────────────────
    public static int findAdjacentSpanIndex(List<FormatSuggestionSpan> spans, int spanStart) {
        for (int i = 0; i < spans.size(); i++) {
            FormatSuggestionSpan s = spans.get(i);
            if (s.getStart() + s.getLength() == spanStart) {
                return i;
            }
        }
        return -1;
    }

    // ─── extendOrAddSpan ──────────────────────────────────────────────────────
    //
    // Adds a span of `spanLen` starting at `spanStart` to the spans list.
    // If a span already ends at `spanStart` (adjacent), extends it instead of
    // adding a new one. After the operation, adjacent spans are merged.
    //
    // Returns a new list (does not mutate the input).
    //
    // Used when extending a format group's span as more runs are processed by
    // a format-retain component or a newline bridge.
    // ─────────────────────────────────────────────────────────────────────────
    public static List<FormatSuggestionSpan> extendOrAddSpan(
            List<FormatSuggestionSpan> spans,
            int spanStart,
            int spanLen
    ) {
        // Copy to avoid mutating the original
        List<FormatSuggestionSpan> next = spans.stream()
                .map(s -> FormatSuggestionSpan.builder()
                        .start(s.getStart()).length(s.getLength()).build())
                .collect(Collectors.toList());

        int adjacentIdx = findAdjacentSpanIndex(next, spanStart);
        if (adjacentIdx != -1) {
            next.get(adjacentIdx).setLength(next.get(adjacentIdx).getLength() + spanLen);
        } else {
            next.add(FormatSuggestionSpan.builder().start(spanStart).length(spanLen).build());
        }

        return next;
    }

    // ─── mergeAdjacentSpans ───────────────────────────────────────────────────
    //
    // Sorts spans by start position and merges any that are contiguous
    // (span[i].end == span[i+1].start). Returns a new compacted list.
    //
    // Called after any operation that might produce adjacent spans (split,
    // shift, extend) to keep the spans list normalised.
    // ─────────────────────────────────────────────────────────────────────────
    public static List<FormatSuggestionSpan> mergeAdjacentSpans(List<FormatSuggestionSpan> spans) {
        if (spans == null || spans.isEmpty()) return new ArrayList<>();

        List<FormatSuggestionSpan> sorted = spans.stream()
                .sorted(Comparator.comparingInt(FormatSuggestionSpan::getStart))
                .collect(Collectors.toList());

        List<FormatSuggestionSpan> merged = new ArrayList<>();
        for (FormatSuggestionSpan span : sorted) {
            if (merged.isEmpty()) {
                merged.add(FormatSuggestionSpan.builder()
                        .start(span.getStart()).length(span.getLength()).build());
            } else {
                FormatSuggestionSpan last = merged.get(merged.size() - 1);
                if (last.getStart() + last.getLength() == span.getStart()) {
                    // Contiguous — extend the last span
                    last.setLength(last.getLength() + span.getLength());
                } else {
                    merged.add(FormatSuggestionSpan.builder()
                            .start(span.getStart()).length(span.getLength()).build());
                }
            }
        }
        return merged;
    }

    // ─── shiftFormatSpansForInsert ────────────────────────────────────────────
    //
    // Adjusts all format suggestion spans to account for new text being inserted
    // at `insertStart` with length `insertLength`.
    //
    // Three cases for each span:
    //   1. Span ends before insertStart  → unchanged (entirely before insertion)
    //   2. Span starts at/after insertStart → shift right by insertLength
    //   3. Span straddles insertStart    → split into left [spanStart, insertStart)
    //                                      and right [insertEnd, spanEnd + insertLength)
    //
    // `skipGroupIds` contains IDs of format groups that ALREADY incorporate the
    // inserted text in their spans (they were extended during this same insert's
    // inherited-attr processing). Those groups must NOT be shifted again.
    //
    // Called immediately after splicing new runs into the runs list, so format
    // span coordinates stay consistent with the run array state.
    // ─────────────────────────────────────────────────────────────────────────
    public static void shiftFormatSpansForInsert(
            List<FormatSuggestionItem> formatSuggestions,
            int insertStart,
            int insertLength,
            Set<String> skipGroupIds
    ) {
        int insertEnd = insertStart + insertLength;

        for (FormatSuggestionItem fmt : formatSuggestions) {
            if (skipGroupIds != null && skipGroupIds.contains(fmt.getGroupId())) {
                continue;
            }

            List<FormatSuggestionSpan> nextSpans = new ArrayList<>();

            for (FormatSuggestionSpan span : fmt.getSpans()) {
                int spanStart = span.getStart();
                int spanEnd = span.getStart() + span.getLength();

                if (spanEnd <= insertStart) {
                    // Entirely before — unchanged
                    nextSpans.add(FormatSuggestionSpan.builder()
                            .start(spanStart).length(span.getLength()).build());

                } else if (spanStart >= insertStart) {
                    // Entirely after — shift right
                    nextSpans.add(FormatSuggestionSpan.builder()
                            .start(spanStart + insertLength).length(span.getLength()).build());

                } else {
                    // Straddles — split into left and right
                    int leftLen = insertStart - spanStart;
                    int rightLen = spanEnd - insertStart;

                    if (leftLen > 0) {
                        nextSpans.add(FormatSuggestionSpan.builder()
                                .start(spanStart).length(leftLen).build());
                    }
                    if (rightLen > 0) {
                        nextSpans.add(FormatSuggestionSpan.builder()
                                .start(insertEnd).length(rightLen).build());
                    }
                }
            }

            List<FormatSuggestionSpan> merged = mergeAdjacentSpans(nextSpans);
            fmt.setSpans(merged);
        }
    }

    // ─── removeRangeFromFormatSuggestion ──────────────────────────────────────
    //
    // Removes the range [start, start+length] from a format suggestion's spans.
    //
    // Used when:
    //   1. A format cancellation is detected — the cancelled range is trimmed out.
    //   2. A delete overlaps a format span — the deleted text is no longer covered.
    //
    // Spans fully outside the range are kept unchanged. Overlapping spans are
    // trimmed to keep only the portions that fall outside the removed range.
    // After trimming, adjacent remaining spans are merged.
    //
    // If no spans remain after removal, the caller is responsible for removing
    // the FormatSuggestionItem from the formatSuggestions list.
    //
    // Mutates the spans list on `item` in place.
    // ─────────────────────────────────────────────────────────────────────────
    public static void removeRangeFromFormatSuggestion(
            FormatSuggestionItem item,
            int start,
            int length
    ) {
        int end = start + length;

        List<FormatSuggestionSpan> next = new ArrayList<>();

        for (FormatSuggestionSpan span : item.getSpans()) {
            int spanStart = span.getStart();
            int spanEnd = span.getStart() + span.getLength();

            if (spanEnd <= start || spanStart >= end) {
                // Entirely outside the removed range — keep unchanged
                next.add(FormatSuggestionSpan.builder()
                        .start(spanStart).length(span.getLength()).build());
                continue;
            }

            // Overlaps the range — keep only the portions outside it
            int leftLen = Math.max(0, start - spanStart);
            int rightLen = Math.max(0, spanEnd - end);

            if (leftLen > 0) {
                next.add(FormatSuggestionSpan.builder()
                        .start(spanStart).length(leftLen).build());
            }
            if (rightLen > 0) {
                next.add(FormatSuggestionSpan.builder()
                        .start(end).length(rightLen).build());
            }
        }

        item.setSpans(next);
    }

    // ─── collectInsertGroupRunsWithAttrs ─────────────────────────────────────
    //
    // Finds all runs belonging to `groupId` that also carry `attrs` in their
    // effective attributes (baseAttributes ∪ suggestionAttributes). Returns their
    // indices and the logical range they span, or null if none are found.
    //
    // Used during insert processing to detect inherited attributes: if actor B
    // inserts text adjacent to actor A's bold insert, we collect A's runs so we
    // can:
    //   1. Strip the inherited attrs from them (they'll be owned by a format suggestion)
    //   2. Compute the correct span for the format suggestion that covers both A's
    //      text and B's newly inserted text.
    // ─────────────────────────────────────────────────────────────────────────
    public static InsertGroupCollection collectInsertGroupRunsWithAttrs(
            List<ReviewRun> runs,
            String groupId,
            Map<String, Object> attrs
    ) {
        List<Integer> indices = new ArrayList<>();
        int start = Integer.MAX_VALUE;
        int end = Integer.MIN_VALUE;

        for (int i = 0; i < runs.size(); i++) {
            ReviewRun run = runs.get(i);
            if (run.getInsertSuggestion() == null
                    || !run.getInsertSuggestion().getGroupId().equals(groupId)) {
                continue;
            }

            Map<String, Object> effectiveAttrs = getEffectiveAttrs(run);
            Map<String, Object> carried = intersectAttrs(effectiveAttrs, attrs);
            if (carried.isEmpty()) continue;

            indices.add(i);
            start = Math.min(start, run.getLogicalStart());
            end = Math.max(end, run.getLogicalStart() + run.getText().length());
        }

        if (indices.isEmpty()) {
            return null;
        }

        return new InsertGroupCollection(indices, start, end);
    }

    // ─── stripAttrsFromRuns ───────────────────────────────────────────────────
    //
    // Removes the specified attribute keys from the given runs (by index).
    //
    // Called after creating or extending a format suggestion that "owns" these
    // attributes — the runs no longer need to carry them directly because the
    // format suggestion overlay will supply them. Removing them prevents the
    // attributes from appearing doubled in the visual delta.
    //
    // Keys are removed from suggestionAttributes first (if present), then from
    // baseAttributes.
    // ─────────────────────────────────────────────────────────────────────────
    public static void stripAttrsFromRuns(
            List<ReviewRun> runs,
            List<Integer> indices,
            Map<String, Object> attrs
    ) {
        String attrKeys = String.join(",", attrs.keySet());
        for (int idx : indices) {
            ReviewRun run = runs.get(idx);
            for (String key : attrs.keySet()) {
                // Remove from suggestionAttributes first (pending layer), then base
                if (run.getSuggestionAttributes() != null
                        && run.getSuggestionAttributes().containsKey(key)) {
                    run.getSuggestionAttributes().remove(key);
                } else if (run.getBaseAttributes() != null) {
                    run.getBaseAttributes().remove(key);
                }
            }
        }
    }

    // ─── findAdjacentFormatGroupByBoundary ────────────────────────────────────
    //
    // Finds a format suggestion whose last (right-most) span ends exactly at
    // `boundaryPos` and whose serialised attributes equal `attrStr`.
    //
    // Used during insert processing to find an existing format suggestion from a
    // different actor that the newly inserted text should be appended to — if the
    // insert falls right at the boundary of such a group, we extend the group's
    // span rather than creating a new one.
    //
    // Returns null if no such group is found.
    // ─────────────────────────────────────────────────────────────────────────
    public static FormatSuggestionItem findAdjacentFormatGroupByBoundary(
            List<FormatSuggestionItem> formatSuggestions,
            String attrStr,
            int boundaryPos
    ) {
        return formatSuggestions.stream()
                .filter(f -> f.getAttributes().equals(attrStr))
                .filter(f -> f.getSpans().stream()
                        .anyMatch(s -> s.getStart() + s.getLength() == boundaryPos))
                .findFirst()
                .orElse(null);
    }

    // ─── extendFormatGroupAtBoundary ──────────────────────────────────────────
    //
    // Extends a format suggestion group's span at `boundaryPos` by `insertLength`
    // characters, and records the new op reference and insert dependency.
    //
    // This is called when inserted text lands at the exact end of an existing
    // format suggestion's span, so the inserted text should be considered part
    // of that suggestion's coverage.
    //
    // Mutates `group` in place.
    // ─────────────────────────────────────────────────────────────────────────
    public static void extendFormatGroupAtBoundary(
            FormatSuggestionItem group,
            int boundaryPos,
            int insertLength,
            String opId,
            int compIdx,
            String currentInsertGroupId
    ) {
        // Find the span that ends at boundaryPos and extend it
        int idx = -1;
        for (int i = 0; i < group.getSpans().size(); i++) {
            FormatSuggestionSpan s = group.getSpans().get(i);
            if (s.getStart() + s.getLength() == boundaryPos) {
                idx = i;
                break;
            }
        }

        if (idx != -1) {
            group.getSpans().get(idx).setLength(group.getSpans().get(idx).getLength() + insertLength);
            group.setSpans(group.getSpans().stream()
                            .map(s -> FormatSuggestionSpan.builder()
                                    .start(s.getStart()).length(s.getLength()).build())
                            .collect(Collectors.toList()
            ));
        }

        // Add op reference if not already present
        boolean refExists = group.getReferences().stream()
                .anyMatch(r -> r.opId().equals(opId) && r.componentIndex() == compIdx);
        if (!refExists) {
            group.getReferences().add(new OpReference(opId, compIdx));
        }

        // Add insert group dependency if not already present
        if (!group.getDependsOnInsertGroupIds().contains(currentInsertGroupId)) {
            group.getDependsOnInsertGroupIds().add(currentInsertGroupId);
        }
    }

    public static boolean isOnlyMeaningfulComponent(Op target, List<Op> ops) {
        int meaningfulCount = 0;
        if (!ops.contains(target)) return false;

        for (Op op : ops) {
            boolean meaningful =
                    op.isInsert()
                            || op.isDelete()
                            || (op.isRetain() && op.getAttributes() != null && !op.getAttributes().isEmpty());

            if (!meaningful) continue;

            meaningfulCount++;
            if (meaningfulCount > 1) return false;
        }

        return meaningfulCount == 1;
    }

    public List<InsertSlice> cloneInsertSlices(List<InsertSlice> slices) {
        if (slices == null) return new ArrayList<>();
        return slices.stream()
                .map(s -> InsertSlice.builder()
                        .start(s.getStart())
                        .length(s.getLength())
                        .ref(new OpReference(s.getRef().opId(), s.getRef().componentIndex()))
                        .build())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public List<InsertSlice> shiftInsertSlices(List<InsertSlice> slices, int delta) {
        return cloneInsertSlices(slices).stream()
                .peek(s -> s.setStart(s.getStart() + delta))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private record InsertSliceSplit(
            List<InsertSlice> left,
            List<InsertSlice> right
    ) {}

    private InsertSliceSplit splitInsertSlices(List<InsertSlice> slices, int offset) {
        List<InsertSlice> left = new ArrayList<>();
        List<InsertSlice> right = new ArrayList<>();

        if (slices == null || slices.isEmpty()) {
            return new InsertSliceSplit(left, right);
        }

        for (InsertSlice slice : slices) {
            int sliceStart = slice.getStart();
            int sliceEnd = slice.getStart() + slice.getLength();

            if (sliceEnd <= offset) {
                left.add(InsertSlice.builder()
                        .start(sliceStart)
                        .length(slice.getLength())
                        .ref(new OpReference(slice.getRef().opId(), slice.getRef().componentIndex()))
                        .build());
            } else if (sliceStart >= offset) {
                right.add(InsertSlice.builder()
                        .start(sliceStart - offset)
                        .length(slice.getLength())
                        .ref(new OpReference(slice.getRef().opId(), slice.getRef().componentIndex()))
                        .build());
            } else {
                int leftLen = offset - sliceStart;
                int rightLen = sliceEnd - offset;

                if (leftLen > 0) {
                    left.add(InsertSlice.builder()
                            .start(sliceStart)
                            .length(leftLen)
                            .ref(new OpReference(slice.getRef().opId(), slice.getRef().componentIndex()))
                            .build());
                }

                if (rightLen > 0) {
                    right.add(InsertSlice.builder()
                            .start(0)
                            .length(rightLen)
                            .ref(new OpReference(slice.getRef().opId(), slice.getRef().componentIndex()))
                            .build());
                }
            }
        }

        return new InsertSliceSplit(left, right);
    }

    public List<OpReference> distinctRefs(List<OpReference> refs) {
        if (refs == null) return new ArrayList<>();
        Map<String, OpReference> unique = new LinkedHashMap<>();
        for (OpReference ref : refs) {
            unique.put(ref.opId() + "::" + ref.componentIndex(), ref);
        }
        return new ArrayList<>(unique.values());
    }

    private int meaningfulCharsBeforeComponent(TextOperation op, int componentIndex) {
        int count = 0;
        for (int i = 0; i < componentIndex; i++) {
            Op part = op.getDelta().ops.get(i);
            if (part.isInsert() && part.getInsert() instanceof String text) {
                count += text.length();
            } else if (part.isRetain() && part.getRetain() instanceof Integer retain) {
                count += retain;
            }
        }
        return count;
    }

    public void commitOrSplitInsertOp(
            List<TextOperation> logOps,
            TextOperation insertOp,
            int insertComponentIndex,
            int overlapLength
    ) {
        Op insertComponent = insertOp.getDelta().ops.get(insertComponentIndex);
        if (insertComponent == null || !(insertComponent.getInsert() instanceof String fullInsertText)) {
            throw new BadRequestException("Could not locate insert component in delta for op: " + insertOp.getOpId());
        }

        int charsBeforeInsert = meaningfulCharsBeforeComponent(insertOp, insertComponentIndex);
        int insertTotalLength = insertComponent.length();

        boolean wholeInsertComponentCovered = overlapLength == insertTotalLength;
        boolean insertIsOnlyMeaningfulComponent =
                isOnlyMeaningfulComponent(insertComponent, insertOp.getDelta().ops);

        if (wholeInsertComponentCovered && insertIsOnlyMeaningfulComponent) {
            insertOp.setState(OpState.COMMITTED);
            return;
        }

        String committedText = fullInsertText.substring(0, overlapLength);
        String remainingText = fullInsertText.substring(overlapLength);

        Delta committedDelta = new Delta();
        if (charsBeforeInsert > 0) {
            committedDelta.retain(charsBeforeInsert, null);
        }
        if (!committedText.isEmpty()) {
            committedDelta.insert(committedText, insertComponent.getAttributes());
        }

        TextOperation committedInsertOp = new TextOperation(
                committedDelta,
                insertOp.getActorEmail(),
                insertOp.getRevision(),
                OpState.COMMITTED,
                insertOp.getCreatedAt()
        );

        Delta remainingDelta = new Delta();
        for (int i = 0; i < insertComponentIndex; i++) {
            remainingDelta.push(insertOp.getDelta().ops.get(i));
        }

        if (overlapLength > 0) {
            remainingDelta.retain(overlapLength, null);
        }
        if (!remainingText.isEmpty()) {
            remainingDelta.insert(remainingText, insertComponent.getAttributes());
        }

        for (int i = insertComponentIndex + 1; i < insertOp.getDelta().ops.size(); i++) {
            remainingDelta.push(insertOp.getDelta().ops.get(i));
        }

        insertOp.setDelta(remainingDelta);

        int insertOpIndex = logOps.indexOf(insertOp);
        logOps.add(insertOpIndex, committedInsertOp);
    }

    public void commitOrSplitDeleteOp(
            List<TextOperation> logOps,
            TextOperation deleteOp,
            int deleteComponentIndex,
            int overlapLength
    ) {
        Op deleteComponent = deleteOp.getDelta().ops.get(deleteComponentIndex);
        if (deleteComponent == null) {
            throw new BadRequestException("Could not locate delete component in delta for op: " + deleteOp.getOpId());
        }

        int charsBeforeDelete = meaningfulCharsBeforeComponent(deleteOp, deleteComponentIndex);
        int deleteTotalLength = deleteComponent.getDelete();

        boolean wholeDeleteComponentCovered = overlapLength == deleteTotalLength;
        boolean deleteIsOnlyMeaningfulComponent =
                isOnlyMeaningfulComponent(deleteComponent, deleteOp.getDelta().ops);

        if (wholeDeleteComponentCovered && deleteIsOnlyMeaningfulComponent) {
            deleteOp.setState(OpState.COMMITTED);
            return;
        }

        Delta committedDeleteDelta = new Delta();
        if (charsBeforeDelete > 0) {
            committedDeleteDelta.retain(charsBeforeDelete, null);
        }
        committedDeleteDelta.delete(overlapLength);

        TextOperation committedDeleteOp = new TextOperation(
                committedDeleteDelta,
                deleteOp.getActorEmail(),
                deleteOp.getRevision(),
                OpState.COMMITTED,
                deleteOp.getCreatedAt()
        );

        Delta remainingDeleteDelta = new Delta();
        for (int i = 0; i < deleteComponentIndex; i++) {
            remainingDeleteDelta.push(deleteOp.getDelta().ops.get(i));
        }

        int remainingDeleteLen = deleteTotalLength - overlapLength;
        if (remainingDeleteLen > 0) {
            remainingDeleteDelta.delete(remainingDeleteLen);
        }

        for (int i = deleteComponentIndex + 1; i < deleteOp.getDelta().ops.size(); i++) {
            remainingDeleteDelta.push(deleteOp.getDelta().ops.get(i));
        }

        deleteOp.setDelta(remainingDeleteDelta);

        int deleteOpIndex = logOps.indexOf(deleteOp);
        logOps.add(deleteOpIndex, committedDeleteOp);
    }

    public static boolean hasReference(List<OpReference> refs, String opId, Integer componentIndex) {
        if (refs == null) return false;
        return refs.stream().anyMatch(r ->
                Objects.equals(r.opId(), opId) &&
                        Objects.equals(r.componentIndex(), componentIndex));
    }

    public static List<OpReference> addReferenceIfMissing(
            List<OpReference> refs,
            String opId,
            Integer componentIndex
    ) {
        List<OpReference> out = refs != null ? new ArrayList<>(refs) : new ArrayList<>();
        if (!hasReference(out, opId, componentIndex)) {
            out.add(new OpReference(opId, componentIndex));
        }
        return out;
    }

    public static boolean hasSliceReference(List<InsertSlice> slices, String opId, Integer componentIndex) {
        if (slices == null) return false;
        return slices.stream().anyMatch(s ->
                s.getRef() != null &&
                        Objects.equals(s.getRef().opId(), opId) &&
                        Objects.equals(s.getRef().componentIndex(), componentIndex));
    }

    // ─── Copy helpers ─────────────────────────────────────────────────────────
    //
    // Shallow-copy helpers used by splitAt() to avoid sharing mutable objects
    // between the left and right halves of a split run.
    // ─────────────────────────────────────────────────────────────────────────

    public static InsertSuggestion copyInsertSuggestion(InsertSuggestion src) {
        if (src == null) return null;
        return InsertSuggestion.builder()
                .groupId(src.getGroupId())
                .actorEmail(src.getActorEmail())
                .createdAt(src.getCreatedAt())
                .references(new ArrayList<>(src.getReferences()))
                .startIndex(src.getStartIndex())
                .build();
    }

    public static DeleteSuggestion copyDeleteSuggestion(DeleteSuggestion src) {
        if (src == null) return null;
        return DeleteSuggestion.builder()
                .groupId(src.getGroupId())
                .actorEmail(src.getActorEmail())
                .createdAt(src.getCreatedAt())
                .references(new ArrayList<>(src.getReferences()))
                .build();
    }

    public static FormatSuggestionItem copyFormatSuggestionItem(FormatSuggestionItem src) {
        if (src == null) return null;
        return FormatSuggestionItem.builder()
                .groupId(src.getGroupId())
                .actorEmail(src.getActorEmail())
                .createdAt(src.getCreatedAt())
                .attributes(src.getAttributes())
                .references(new ArrayList<>(src.getReferences()))
                .spans(src.getSpans().stream()
                        .map(s -> FormatSuggestionSpan.builder()
                                .start(s.getStart()).length(s.getLength()).build())
                        .collect(Collectors.toList()))
                .previewText(src.getPreviewText())
                .dependsOnInsertGroupIds(new ArrayList<>(src.getDependsOnInsertGroupIds()))
                .build();
    }

    // ─── InsertGroupCollection ────────────────────────────────────────────────
    //
    // Simple return type for collectInsertGroupRunsWithAttrs().
    // Bundles the run indices and their logical span boundaries together.
    // ─────────────────────────────────────────────────────────────────────────
    public static class InsertGroupCollection {
        public final List<Integer> indices;
        public final int start;
        public final int end;

        public InsertGroupCollection(List<Integer> indices, int start, int end) {
            this.indices = indices;
            this.start = start;
            this.end = end;
        }
    }
}