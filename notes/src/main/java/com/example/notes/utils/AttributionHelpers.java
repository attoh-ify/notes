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
            if (refVal != null && Objects.equals(refVal, entry.getValue())) {
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

    public static Map<String, Object> getEffectiveAttrs(ReviewRun run) {
        if (run == null) return Collections.emptyMap();
        return applyAttrsForEffectiveView(
                run.getBaseAttributes(),
                run.getSuggestionAttributes()
        );
    }

    public static Map<String, Object> parseAttrs(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[PARSE_ATTRS] Failed to parse attribute JSON: {} — returning empty map", json);
            return Collections.emptyMap();
        }
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

        if (idx != -1) {
            group.getSpans().get(idx).setLength(
                    group.getSpans().get(idx).getLength() + insertLength
            );
            group.setSpans(mergeAdjacentSpans(group.getSpans()));
        }

        group.setReferences(addComponentLocalSlice(
                group.getReferences(),
                0,
                insertLength,
                opId,
                compIdx
        ));

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
            int acceptedStart,
            int acceptedLength
    ) {
        Op insertComponent = insertOp.getDelta().ops.get(insertComponentIndex);
        if (insertComponent == null || !(insertComponent.getInsert() instanceof String fullInsertText)) {
            throw new BadRequestException("Could not locate insert component in delta for op: " + insertOp.getOpId());
        }

        int componentLength = fullInsertText.length();
        int start = Math.max(0, Math.min(acceptedStart, componentLength));
        int end = Math.max(start, Math.min(start + acceptedLength, componentLength));

        if (end <= start) return;

        Delta committedDelta = new Delta();
        Delta remainingDelta = new Delta();

        for (int i = 0; i < insertComponentIndex; i++) {
            Op op = insertOp.getDelta().ops.get(i);
            committedDelta.push(retainEquivalent(op));
            remainingDelta.push(op);
        }

        if (start > 0) {
            committedDelta.retain(start, null);
            remainingDelta.insert(fullInsertText.substring(0, start), insertComponent.getAttributes());
        }

        committedDelta.insert(fullInsertText.substring(start, end), insertComponent.getAttributes());
        remainingDelta.retain(end - start, null);

        if (end < componentLength) {
            committedDelta.retain(componentLength - end, null);
            remainingDelta.insert(fullInsertText.substring(end), insertComponent.getAttributes());
        }

        for (int i = insertComponentIndex + 1; i < insertOp.getDelta().ops.size(); i++) {
            Op op = insertOp.getDelta().ops.get(i);
            committedDelta.push(retainEquivalent(op));
            remainingDelta.push(op);
        }

        if (remainingDelta.ops.isEmpty()) {
            insertOp.setState(OpState.COMMITTED);
            return;
        }

        TextOperation committedInsertOp = new TextOperation(
                committedDelta,
                insertOp.getActorEmail(),
                insertOp.getRevision(),
                OpState.COMMITTED,
                insertOp.getCreatedAt()
        );

        insertOp.setDelta(remainingDelta);

        int insertOpIndex = logOps.indexOf(insertOp);
        logOps.add(insertOpIndex, committedInsertOp);
    }

    private Op retainEquivalent(Op op) {
        Op retainOp = new Op();
        retainOp.setRetain(op.length());
        retainOp.setAttributes(null);
        return retainOp;
    }

    public void commitOrSplitDeleteOp(
            List<TextOperation> logOps,
            TextOperation deleteOp,
            int deleteComponentIndex,
            int acceptedStart,
            int acceptedLength
    ) {
        Op deleteComponent = deleteOp.getDelta().ops.get(deleteComponentIndex);
        if (deleteComponent == null || !deleteComponent.isDelete()) {
            throw new BadRequestException("Could not locate delete component in delta for op: " + deleteOp.getOpId());
        }

        int componentLength = deleteComponent.getDelete();
        int start = Math.max(0, Math.min(acceptedStart, componentLength));
        int end = Math.max(start, Math.min(start + acceptedLength, componentLength));

        if (end <= start) return;

        Delta committedDelta = new Delta();
        Delta remainingDelta = new Delta();

        for (int i = 0; i < deleteComponentIndex; i++) {
            Op op = deleteOp.getDelta().ops.get(i);
            committedDelta.push(op.isRetain() ? op : retainEquivalent(op));
            remainingDelta.push(op);
        }

        if (start > 0) {
            committedDelta.retain(start, null);
            remainingDelta.delete(start);
        }

        committedDelta.delete(end - start);

        if (end < componentLength) {
            remainingDelta.delete(componentLength - end);
        }

        for (int i = deleteComponentIndex + 1; i < deleteOp.getDelta().ops.size(); i++) {
            Op op = deleteOp.getDelta().ops.get(i);
            committedDelta.push(op.isRetain() ? op : retainEquivalent(op));
            remainingDelta.push(op);
        }

        if (remainingDelta.ops.isEmpty()) {
            deleteOp.setState(OpState.COMMITTED);
            return;
        }

        TextOperation committedDeleteOp = new TextOperation(
                committedDelta,
                deleteOp.getActorEmail(),
                deleteOp.getRevision(),
                OpState.COMMITTED,
                deleteOp.getCreatedAt()
        );

        deleteOp.setDelta(remainingDelta);

        int deleteOpIndex = logOps.indexOf(deleteOp);
        logOps.add(deleteOpIndex, committedDeleteOp);
    }

    public static List<SuggestionSlice> cloneSuggestionSlices(List<SuggestionSlice> slices) {
        if (slices == null) return new ArrayList<>();

        return slices.stream()
                .map(s -> SuggestionSlice.builder()
                        .start(s.getStart())
                        .length(s.getLength())
                        .ref(new OpReference(s.getRef().opId(), s.getRef().componentIndex()))
                        .build())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public static List<SuggestionSlice> addSuggestionSlice(
            List<SuggestionSlice> slices,
            int start,
            int length,
            String opId,
            int componentIndex
    ) {
        List<SuggestionSlice> out = cloneSuggestionSlices(slices);

        out.add(SuggestionSlice.builder()
                .start(start)
                .length(length)
                .ref(new OpReference(opId, componentIndex))
                .build());

        return out;
    }

    public static List<SuggestionSlice> mergeSuggestionSlices(
            List<SuggestionSlice> a,
            List<SuggestionSlice> b
    ) {
        List<SuggestionSlice> out = new ArrayList<>();
        out.addAll(cloneSuggestionSlices(a));
        out.addAll(cloneSuggestionSlices(b));
        return out;
    }

    public static List<OpReference> refsFromSlices(List<SuggestionSlice> slices) {
        if (slices == null) return new ArrayList<>();

        Map<String, OpReference> out = new LinkedHashMap<>();

        for (SuggestionSlice slice : slices) {
            if (slice.getRef() == null) continue;
            OpReference ref = slice.getRef();
            out.put(ref.opId() + "::" + ref.componentIndex(), ref);
        }

        return new ArrayList<>(out.values());
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

        int runCursor = 0;

        for (SuggestionSlice slice : slices) {
            int sliceRunStart = runCursor;
            int sliceRunEnd = runCursor + slice.getLength();

            if (sliceRunEnd <= offset) {
                left.add(SuggestionSlice.builder()
                        .start(slice.getStart())
                        .length(slice.getLength())
                        .ref(new OpReference(slice.getRef().opId(), slice.getRef().componentIndex()))
                        .build());
            } else if (sliceRunStart >= offset) {
                right.add(SuggestionSlice.builder()
                        .start(slice.getStart())
                        .length(slice.getLength())
                        .ref(new OpReference(slice.getRef().opId(), slice.getRef().componentIndex()))
                        .build());
            } else {
                int leftLen = offset - sliceRunStart;
                int rightLen = sliceRunEnd - offset;

                if (leftLen > 0) {
                    left.add(SuggestionSlice.builder()
                            .start(slice.getStart())
                            .length(leftLen)
                            .ref(new OpReference(slice.getRef().opId(), slice.getRef().componentIndex()))
                            .build());
                }

                if (rightLen > 0) {
                    right.add(SuggestionSlice.builder()
                            .start(slice.getStart() + leftLen)
                            .length(rightLen)
                            .ref(new OpReference(slice.getRef().opId(), slice.getRef().componentIndex()))
                            .build());
                }
            }

            runCursor += slice.getLength();
        }

        return new SuggestionSliceSplit(left, right);
    }

    public static InsertSuggestion copyInsertSuggestion(InsertSuggestion src) {
        if (src == null) return null;

        return InsertSuggestion.builder()
                .groupId(src.getGroupId())
                .actorEmail(src.getActorEmail())
                .createdAt(src.getCreatedAt())
                .references(cloneSuggestionSlices(src.getReferences()))
                .startIndex(src.getStartIndex())
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

    public static int componentTextLength(Op op) {
        if (op == null) return 0;

        if (op.isInsert() && op.getInsert() instanceof String text) {
            return text.length();
        }

        if (op.isDelete()) {
            return op.getDelete();
        }

        if (op.isRetain()) {
            return (Integer) op.getRetain();
        }

        return 0;
    }

    public static List<SuggestionSlice> addComponentLocalSlice(
            List<SuggestionSlice> slices,
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