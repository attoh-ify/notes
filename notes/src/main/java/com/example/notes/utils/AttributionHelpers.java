package com.example.notes.utils;

import com.example.notes.dto.attribution.*;
import com.example.notes.dto.note.OpReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@UtilityClass
public class AttributionHelpers {
    static final ObjectMapper MAPPER = new ObjectMapper();

    private static int groupCtr = 0;

    public static void resetGroupCounter() {
        groupCtr = 0;
    }

    public static String nextId() {
        return "g_" + (++groupCtr);
    }

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

    public static Map<String, Object> intersectAttrs(
            Map<String, Object> candidate,
            Map<String, Object> reference
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (candidate == null || reference == null) return out;
        for (Map.Entry<String, Object> entry : candidate.entrySet()) {
            Object refVal = reference.get(entry.getKey());
            if (Objects.equals(refVal, entry.getValue())) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

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

    public static Map<String, Object> getEffectiveAttrs(ReviewRun run) {
        if (run == null) return Collections.emptyMap();

        Map<String, Object> out = new LinkedHashMap<>(
                run.getBaseAttributes() != null ? run.getBaseAttributes() : Collections.emptyMap()
        );
        out.putAll(run.getSuggestionAttributes());

        return out;
    }

    public static String attrsToJson(Map<String, Object> attrs) {
        if (attrs == null || attrs.isEmpty()) return "{}";
        try {
            return MAPPER.writeValueAsString(new TreeMap<>(attrs));
        } catch (Exception e) {
            log.warn("[ATTRS_TO_JSON] Serialisation failed — returning {}", e.getMessage());
            return "{}";
        }
    }

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

    public static int splitAt(List<ReviewRun> runs, int idx, int offset) {
        if (idx >= runs.size() || offset <= 0 || offset >= runs.get(idx).getText().length()) {
            return idx;
        }

        ReviewRun r = runs.get(idx);

        InsertSuggestion leftInsert = copyInsertSuggestion(r.getInsertSuggestion());
        InsertSuggestion rightInsert = copyInsertSuggestion(r.getInsertSuggestion());

        if (r.getInsertSuggestion() != null) {
            SuggestionSliceSplit split = splitSuggestionSlices(
                    r.getInsertSuggestion().getReferences(),
                    offset
            );
            leftInsert.setReferences(split.left());
            rightInsert.setReferences(split.right());
        }

        DeleteSuggestion leftDelete = copyDeleteSuggestion(r.getDeleteSuggestion());
        DeleteSuggestion rightDelete = copyDeleteSuggestion(r.getDeleteSuggestion());

        if (r.getDeleteSuggestion() != null) {
            SuggestionSliceSplit split = splitSuggestionSlices(
                    r.getDeleteSuggestion().getReferences(),
                    offset
            );
            leftDelete.setReferences(split.left());
            rightDelete.setReferences(split.right());
        }

        ReviewRun left = ReviewRun.builder()
                .text(r.getText().substring(0, offset))
                .baseAttributes(new LinkedHashMap<>(r.getBaseAttributes() != null ? r.getBaseAttributes() : Collections.emptyMap()))
                .suggestionAttributes(new LinkedHashMap<>(r.getSuggestionAttributes() != null ? r.getSuggestionAttributes() : Collections.emptyMap()))
                .logicalStart(r.getLogicalStart())
                .insertSuggestion(leftInsert)
                .deleteSuggestion(leftDelete)
                .build();

        ReviewRun right = ReviewRun.builder()
                .text(r.getText().substring(offset))
                .baseAttributes(new LinkedHashMap<>(r.getBaseAttributes() != null ? r.getBaseAttributes() : Collections.emptyMap()))
                .suggestionAttributes(new LinkedHashMap<>(r.getSuggestionAttributes() != null ? r.getSuggestionAttributes() : Collections.emptyMap()))
                .logicalStart(r.getLogicalStart() + offset)
                .insertSuggestion(rightInsert)
                .deleteSuggestion(rightDelete)
                .build();

        runs.set(idx, left);
        runs.add(idx + 1, right);

        return idx + 1;
    }

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

    public static int findAdjacentSpanIndex(List<FormatSuggestionSpan> spans, int spanStart) {
        for (int i = 0; i < spans.size(); i++) {
            FormatSuggestionSpan s = spans.get(i);
            if (s.getStart() + s.getLength() == spanStart) {
                return i;
            }
        }
        return -1;
    }

    public static List<FormatSuggestionSpan> mergeAdjacentSpans(List<FormatSuggestionSpan> spans) {
        if (spans == null || spans.isEmpty()) return new ArrayList<>();

        List<FormatSuggestionSpan> sorted = spans.stream()
                .sorted(Comparator.comparingInt(FormatSuggestionSpan::getStart))
                .toList();

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

    public static void extendFormatGroupAtBoundary(
            FormatSuggestionItem group,
            int boundaryPos,
            int insertLength,
            String opId,
            int compIdx,
            String currentInsertGroupId
    ) {
        int idx = -1;

        for (int i = 0; i < group.getSpans().size(); i++) {
            FormatSuggestionSpan s = group.getSpans().get(i);
            if (s.getStart() + s.getLength() == boundaryPos) {
                idx = i;
                break;
            }
        }

        if (idx == -1) {
            return;
        }

        FormatSuggestionSpan span = group.getSpans().get(idx);

        span.setLength(span.getLength() + insertLength);
        group.setSpans(mergeAdjacentSpans(group.getSpans()));

        group.setReferences(addComponentLocalSlice(
                group.getReferences(),
                0,
                0,
                insertLength,
                opId,
                compIdx
        ));

        if (!group.getDependsOnInsertGroupIds().contains(currentInsertGroupId)) {
            group.getDependsOnInsertGroupIds().add(currentInsertGroupId);
        }
    }

    public static List<SuggestionSlice> cloneSuggestionSlices(List<SuggestionSlice> slices) {
        if (slices == null) return new ArrayList<>();

        return slices.stream()
                .map(s -> SuggestionSlice.builder()
                        .reviewStart(s.getReviewStart())
                        .componentStart(s.getComponentStart())
                        .length(s.getLength())
                        .ref(new OpReference(s.getRef().opId(), s.getRef().componentIndex()))
                        .build())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public static SuggestionSlice cloneSlice(SuggestionSlice s) {
        return SuggestionSlice.builder()
                .reviewStart(s.getReviewStart()
                )
                .componentStart(s.getComponentStart())
                .length(s.getLength())
                .ref(new OpReference(s.getRef().opId(), s.getRef().componentIndex()))
                .build();
    }

    public static SuggestionSliceSplit splitSuggestionSlices(
            List<SuggestionSlice> slices,
            int offset
    ) {
        List<SuggestionSlice> left = new ArrayList<>();
        List<SuggestionSlice> right = new ArrayList<>();

        if (slices == null || slices.isEmpty()) {
            return new SuggestionSliceSplit(left, right);
        }

        for (SuggestionSlice slice : slices) {
            int start = slice.getReviewStart();
            int end = start + slice.getLength();

            if (end <= offset) {
                left.add(cloneSlice(slice));
            } else if (start >= offset) {
                SuggestionSlice shifted = cloneSlice(slice);
                shifted.setReviewStart(start - offset);
                right.add(shifted);
            } else {
                int leftLen = offset - start;
                int rightLen = end - offset;

                if (leftLen > 0) {
                    left.add(SuggestionSlice.builder()
                            .reviewStart(start)
                            .componentStart(slice.getComponentStart())
                            .length(leftLen)
                            .ref(new OpReference(slice.getRef().opId(), slice.getRef().componentIndex()))
                            .build());
                }

                if (rightLen > 0) {
                    right.add(SuggestionSlice.builder()
                            .reviewStart(0)
                            .componentStart(slice.getComponentStart() + leftLen)
                            .length(rightLen)
                            .ref(new OpReference(slice.getRef().opId(), slice.getRef().componentIndex()))
                            .build());
                }
            }
        }

        return new SuggestionSliceSplit(left, right);
    }

    public static List<SuggestionSlice> appendSuggestionSlices(
            List<SuggestionSlice> base,
            List<SuggestionSlice> incoming
    ) {
        List<SuggestionSlice> out = new ArrayList<>(base);

        for (SuggestionSlice s : incoming) {
            out.add(cloneSlice(s));
        }

        return out;
    }

    public static InsertSuggestion copyInsertSuggestion(InsertSuggestion src) {
        if (src == null) return null;

        return InsertSuggestion.builder()
                .groupId(src.getGroupId())
                .actorEmail(src.getActorEmail())
                .createdAt(src.getCreatedAt())
                .references(cloneSuggestionSlices(src.getReferences()))
                .build();
    }

    public static DeleteSuggestion copyDeleteSuggestion(DeleteSuggestion src) {
        if (src == null) return null;

        return DeleteSuggestion.builder()
                .groupId(src.getGroupId())
                .actorEmail(src.getActorEmail())
                .createdAt(src.getCreatedAt())
                .references(cloneSuggestionSlices(src.getReferences()))
                .build();
    }

    public static List<SuggestionSlice> addSuggestionSlice(
            List<SuggestionSlice> slices,
            int reviewStart,
            int componentStart,
            int length,
            String opId,
            int componentIndex
    ) {
        List<SuggestionSlice> out = cloneSuggestionSlices(slices);

        out.add(SuggestionSlice.builder()
                .reviewStart(reviewStart)
                .componentStart(componentStart)
                .length(length)
                .ref(new OpReference(opId, componentIndex))
                .build());

        return out;
    }

    public static List<SuggestionSlice> addComponentLocalSlice(
            List<SuggestionSlice> slices,
            int reviewStart,
            int componentStart,
            int length,
            String opId,
            int componentIndex
    ) {
        if (length <= 0) {
            return cloneSuggestionSlices(slices);
        }

        return addSuggestionSlice(
                slices,
                reviewStart,
                componentStart,
                length,
                opId,
                componentIndex
        );
    }

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

    public record SuggestionSliceSplit(
            List<SuggestionSlice> left,
            List<SuggestionSlice> right
    ) {}
}