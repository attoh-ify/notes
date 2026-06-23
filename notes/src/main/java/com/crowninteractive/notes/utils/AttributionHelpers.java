package com.crowninteractive.notes.utils;

import com.crowninteractive.notes.dto.attribution.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@UtilityClass
public class AttributionHelpers {
    static final ObjectMapper MAPPER = new ObjectMapper();

    private static int groupCtr = 0;

    // ─────────────────────────────────────────────────────────────────────────
    // Group counter
    // ─────────────────────────────────────────────────────────────────────────
    public static void resetGroupCounter() {
        groupCtr = 0;
    }

    public static String nextId() {
        return "g_" + (++groupCtr);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Attribute helpers
    // ─────────────────────────────────────────────────────────────────────────

    public static boolean attrsEq(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Object> aa = (a != null) ? a : Collections.emptyMap();
        Map<String, Object> bb = (b != null) ? b : Collections.emptyMap();

        try {
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
        if (remove != null) remove.keySet().forEach(out::remove);

        return out;
    }

    public static Map<String, Object> overlayAttrsPreserveNull(
            Map<String, Object> baseAttrs,
            Map<String, Object> deltaAttrs
    ) {
        Map<String, Object> out = new LinkedHashMap<>(baseAttrs != null ? baseAttrs : Collections.emptyMap());
        if (deltaAttrs != null) out.putAll(deltaAttrs);
        return out;
    }

    public static Map<String, Object> getEffectiveAttrs(Segment run) {
        if (run == null) return Collections.emptyMap();

        Map<String, Object> out = new LinkedHashMap<>(
                run.getBaseAttributes() != null ? run.getBaseAttributes() : Collections.emptyMap());
        out.putAll(run.getChangeAttributes());
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Run position
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Locate a logical document position inside the Segment list.
     * logicalPos ignores deleted-change runs because deleted runs are visible
     * in audit mode but do not count as live document text.
     * absPos tracks the visual/runtime position, including deleted runs.
     */
    public static RunPosition findRunPos(List<Segment> runs, int logicalPos) {
        int pos = 0;
        int absPos = 0;

        for (int i = 0; i < runs.size(); i++) {
            Segment r = runs.get(i);

            int runLen = r.length();

            if (r.getDeleteChange() != null) {
                absPos += runLen;
                continue;
            }

            if (pos == logicalPos) {
                return new RunPosition(i, 0, absPos);
            }

            if (pos + runLen > logicalPos) {
                int off = logicalPos - pos;
                return new RunPosition(i, off, absPos + off);
            }

            pos += runLen;
            absPos += runLen;
        }

        return new RunPosition(runs.size(), 0, absPos);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Run splitting
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Split a run at a specific offset.
     * Returns the index of the RIGHT half (the run starting at the split point).
     */
    public static int splitAt(List<Segment> runs, int idx, int offset) {
        if (idx >= runs.size()) {
            return idx;
        }

        Segment r = runs.get(idx);

        if (r.isEmbed()) {
            return idx;
        }

        if (offset <= 0 || offset >= r.length()) {
            return idx;
        }

        int splitAbsPos = r.getLogicalStart() + offset;

        ReferenceSplit split = splitReferences(r.getReferences(), splitAbsPos);

        Segment left = Segment.builder()
                .id(r.getId() != null ? r.getId() + "_L_" + splitAbsPos : null)
                .text(r.getText().substring(0, offset))
                .baseAttributes(new LinkedHashMap<>(r.getBaseAttributes() != null ? r.getBaseAttributes() : Collections.emptyMap()))
                .changeAttributes(new LinkedHashMap<>(r.getChangeAttributes() != null ? r.getChangeAttributes() : Collections.emptyMap()))
                .references(split.getLeft())
                .logicalStart(r.getLogicalStart())
                .insertChange(copyInsertChange(r.getInsertChange()))
                .deleteChange(copyDeleteChange(r.getDeleteChange()))
                .build();

        Segment right = Segment.builder()
                .id(r.getId() != null ? r.getId() + "_R_" + splitAbsPos : null)
                .text(r.getText().substring(offset))
                .baseAttributes(new LinkedHashMap<>(r.getBaseAttributes() != null ? r.getBaseAttributes() : Collections.emptyMap()))
                .changeAttributes(new LinkedHashMap<>(r.getChangeAttributes() != null ? r.getChangeAttributes() : Collections.emptyMap()))
                .references(split.getRight())
                .logicalStart(splitAbsPos)
                .insertChange(copyInsertChange(r.getInsertChange()))
                .deleteChange(copyDeleteChange(r.getDeleteChange()))
                .build();

        runs.set(idx, left);
        runs.add(idx + 1, right);

        return idx + 1;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Newline-only retain check
    // ─────────────────────────────────────────────────────────────────────────

    public static boolean isOnlyNewlineRetain(
            List<Segment> runs,
            int logicalStart,
            int retainLength
    ) {
        RunPosition pos = findRunPos(runs, logicalStart);
        int runIdx = pos.getIdx();
        int offset = pos.getOffset();
        int remaining = retainLength;
        boolean sawOverlap = false;

        for (int i = runIdx; i < runs.size() && remaining > 0; i++) {
            Segment run = runs.get(i);
            if (run.getDeleteChange() != null) continue;

            sawOverlap = true;
            int lenToCheck = Math.min(run.length() - offset, remaining);

            if (run.isEmbed()) return false;
            for (int j = offset; j < offset + lenToCheck; j++) {
                if (run.getText().charAt(j) != '\n') {
                    return false;
                }
            }

            remaining -= lenToCheck;
            offset = 0;
        }

        return sawOverlap;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Format range helpers
    // ─────────────────────────────────────────────────────────────────────────


    public static List<ReviewRange> mergeOverlappingRanges(List<ReviewRange> ranges) {
        if (ranges == null || ranges.isEmpty()) return new ArrayList<>();

        List<ReviewRange> sorted = ranges.stream()
                .sorted(Comparator.comparingInt(ReviewRange::getStart))
                .collect(Collectors.toList());

        List<ReviewRange> merged = new ArrayList<>();

        for (ReviewRange range : sorted) {
            int start = range.getStart();
            int end = start + range.getLength();

            if (merged.isEmpty()) {
                merged.add(ReviewRange.builder().start(start).length(range.getLength()).build());
                continue;
            }

            ReviewRange last = merged.get(merged.size() - 1);
            int lastStart = last.getStart();
            int lastEnd = lastStart + last.getLength();

            if (start <= lastEnd) {
                last.setLength(Math.max(lastEnd, end) - lastStart);
            } else {
                merged.add(ReviewRange.builder().start(start).length(range.getLength()).build());
            }
        }

        return merged;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Insert group collection
    // ─────────────────────────────────────────────────────────────────────────

    public static InsertGroupCollection collectInsertGroupRunsWithAttrs(
            List<Segment> runs,
            String groupId,
            Map<String, Object> attrs
    ) {
        List<Integer> indices = new ArrayList<>();
        int start = Integer.MAX_VALUE;
        int end = Integer.MIN_VALUE;

        for (int i = 0; i < runs.size(); i++) {
            Segment run = runs.get(i);

            if (run.getInsertChange() == null
                    || !run.getInsertChange().getGroupId().equals(groupId)) continue;

            Map<String, Object> effectiveAttrs = getEffectiveAttrs(run);
            Map<String, Object> carried = intersectAttrs(effectiveAttrs, attrs);

            if (carried.isEmpty()) continue;

            indices.add(i);
            start = Math.min(start, run.getLogicalStart());
            end = Math.max(end, run.getLogicalStart() + run.length());
        }

        if (indices.isEmpty()) {
            return null;
        }

        return new InsertGroupCollection(indices, start, end);
    }

    public static void moveAttrsFromBaseToChangeForRuns(
            List<Segment> runs,
            List<Integer> indices,
            Map<String, Object> attrs
    ) {
        if (runs == null || indices == null || attrs == null || attrs.isEmpty()) return;

        for (Integer idx : indices) {
            if (idx == null || idx < 0 || idx >= runs.size()) {
                continue;
            }

            Segment run = runs.get(idx);
            Map<String, Object> base = new LinkedHashMap<>(run.getBaseAttributes() != null ? run.getBaseAttributes() : Collections.emptyMap());
            Map<String, Object> change = new LinkedHashMap<>(run.getChangeAttributes() != null ? run.getChangeAttributes() : Collections.emptyMap());

            for (Map.Entry<String, Object> entry : attrs.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (Objects.equals(base.get(key), value)) {
                    base.remove(key);
                }
                change.put(key, value);
            }

            run.setBaseAttributes(base);
            run.setChangeAttributes(change);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Format change find/create
    // ─────────────────────────────────────────────────────────────────────────

    public static FormatChangeItem findOrCreateFormatChangeByIdentity(
            List<FormatChangeItem> formatChanges,
            String actorEmail,
            String createdAt,
            String attrKey,
            Object attrValue
    ) {
        FormatChangeItem existing = formatChanges.stream()
                .filter(f -> actorEmail.equals(f.getActorEmail()))
                .filter(f -> attrKey.equals(f.getAttributeKey()))
                .filter(f -> Objects.equals(attrValue, f.getAttributeValue()))
                .findFirst().orElse(null);

        if (existing != null) {
            if (createdAt != null && existing.getCreatedAt() != null
                    && createdAt.compareTo(existing.getCreatedAt()) > 0) {
                existing.setCreatedAt(createdAt);
            }
            return existing;
        }

        FormatChangeItem created = FormatChangeItem.builder()
                .groupId(nextId())
                .actorEmail(actorEmail)
                .createdAt(createdAt)
                .attributeKey(attrKey)
                .attributeValue(attrValue)
                .references(new ArrayList<>())
                .previewText("")
                .dependsOnInsertGroupIds(new ArrayList<>())
                .dependsOnDeleteGroupIds(new ArrayList<>())
                .build();

        formatChanges.add(created);
        return created;
    }

    public static void addInsertDependency(FormatChangeItem item, String insertGroupId) {
        if (item == null || insertGroupId == null) return;
        if (!item.getDependsOnInsertGroupIds().contains(insertGroupId)) {
            item.getDependsOnInsertGroupIds().add(insertGroupId);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reference helpers
    // ─────────────────────────────────────────────────────────────────────────

    public static List<Reference> cloneReferences(List<Reference> references) {
        if (references == null) return new ArrayList<>();
        return references.stream().map(AttributionHelpers::cloneReference)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public static Reference cloneReference(Reference s) {
        if (s == null) return null;
        return Reference.builder()
                .reviewStart(s.getReviewStart())
                .componentStart(s.getComponentStart())
                .length(s.getLength())
                .opId(s.getOpId())
                .componentIndex(s.getComponentIndex())
                .build();
    }

    /**
     * Split source references when a ReviewRun is split.
     * Left run keeps references entirely before splitAbsPos.
     * Right run keeps references at or after splitAbsPos.
     * References straddling splitAbsPos are split into left and right pieces.
     */
    public static ReferenceSplit splitReferences(
            List<Reference> references,
            int splitAbsPos
    ) {
        List<Reference> left = new ArrayList<>();
        List<Reference> right = new ArrayList<>();

        if (references == null || references.isEmpty()) {
            return new ReferenceSplit(left, right);
        }

        for (Reference reference : references) {
            int start = reference.getReviewStart();
            int end = start + reference.getLength();

            if (end <= splitAbsPos) {
                left.add(cloneReference(reference));

            } else if (start >= splitAbsPos) {
                right.add(cloneReference(reference));

            } else {
                int leftLen = splitAbsPos - start;
                int rightLen = end - splitAbsPos;

                if (leftLen > 0) {
                    left.add(Reference.builder()
                            .reviewStart(start)
                            .componentStart(reference.getComponentStart())
                            .length(leftLen)
                            .opId(reference.getOpId())
                            .componentIndex(reference.getComponentIndex())
                            .build());
                }

                if (rightLen > 0) {
                    right.add(Reference.builder()
                            .reviewStart(splitAbsPos)
                            .componentStart(reference.getComponentStart() + leftLen)
                            .length(rightLen)
                            .opId(reference.getOpId())
                            .componentIndex(reference.getComponentIndex())
                            .build());
                }
            }
        }

        return new ReferenceSplit(left, right);
    }

    public static List<Reference> appendReferences(
            List<Reference> base,
            List<Reference> incoming
    ) {
        List<Reference> out = new ArrayList<>();
        if (base != null) for (Reference s : base) out = appendAndCoalesceReference(out, s);
        if (incoming != null) for (Reference s : incoming) out = appendAndCoalesceReference(out, s);
        return out;
    }

    public static InsertChange copyInsertChange(InsertChange src) {
        if (src == null) return null;
        return InsertChange.builder()
                .groupId(src.getGroupId())
                .actorEmail(src.getActorEmail())
                .createdAt(src.getCreatedAt())
                .build();
    }

    public static DeleteChange copyDeleteChange(DeleteChange src) {
        if (src == null) return null;
        return DeleteChange.builder()
                .groupId(src.getGroupId())
                .actorEmail(src.getActorEmail())
                .createdAt(src.getCreatedAt())
                .type(src.getType() != null
                        ? src.getType()
                        : DeleteChange.DeleteChangeType.TEXT)
                .build();
    }

    /**
     * Add a provenance reference for a change.
     * reviewStart    = where this slice appears in the runtime review document.
     * componentStart = where this slice starts inside the original op component text.
     */
    public static List<Reference> addReference(
            List<Reference> references,
            int reviewStart,
            int componentStart,
            int length,
            String opId,
            int componentIndex
    ) {
        List<Reference> out = cloneReferences(references);

        if (length <= 0) {
            return out;
        }

        Reference incoming = Reference.builder()
                .reviewStart(reviewStart)
                .componentStart(componentStart)
                .length(length)
                .opId(opId)
                .componentIndex(componentIndex)
                .build();

        return appendAndCoalesceReference(out, incoming);
    }

    public static List<Reference> appendAndCoalesceReference(
            List<Reference> references,
            Reference incoming
    ) {
        List<Reference> out = cloneReferences(references);

        if (incoming == null || incoming.getLength() <= 0) return out;

        if (!out.isEmpty()) {
            Reference last = out.get(out.size() - 1);
            if (canCoalesceReferences(last, incoming)) {
                last.setLength(last.getLength() + incoming.getLength());
                return out;
            }
        }

        out.add(cloneReference(incoming));
        return out;
    }

    /**
     * References can be coalesced only when they are adjacent in both:
     *   - runtime review space (reviewStart)
     *   - original component space (componentStart)
     * And come from the same op and component.
     */
    public static boolean canCoalesceReferences(Reference left, Reference right) {
        if (left == null || right == null) return false;

        boolean sameRef = Objects.equals(left.getOpId(), right.getOpId())
                && Objects.equals(left.getComponentIndex(), right.getComponentIndex());
        boolean adjacentReview = left.getReviewStart() + left.getLength() == right.getReviewStart();
        boolean adjacentComponent = left.getComponentStart() + left.getLength() == right.getComponentStart();

        return sameRef && adjacentReview && adjacentComponent;
    }

    /**
     * When new text is inserted, existing change references after the insert
     * point must move forward.
     * References belonging to the newly inserted group are skipped.
     */
    public void shiftChangeReferenceReviewStarts(
            List<Segment> runs,
            int insertPos,
            int shiftLen,
            String insertedGroupId
    ) {
        if (shiftLen <= 0) {
            return;
        }

        for (Segment run : runs) {
            boolean belongsToInsertedGroup =
                    run.getInsertChange() != null
                            && Objects.equals(insertedGroupId, run.getInsertChange().getGroupId());

            boolean hasChangeRefs =
                    run.getInsertChange() != null
                            || run.getDeleteChange() != null;

            if (!hasChangeRefs || belongsToInsertedGroup) {
                continue;
            }

            for (Reference reference : run.getReferences()) {
                if (reference.getReviewStart() >= insertPos) {
                    reference.setReviewStart(reference.getReviewStart() + shiftLen);
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Range derivation
    // ─────────────────────────────────────────────────────────────────────────

    public static List<ReviewRange> deriveMergedRangesFromReferences(
            List<Reference> references
    ) {
        if (references == null || references.isEmpty()) return new ArrayList<>();

        List<ReviewRange> raw = new ArrayList<>();
        for (Reference ref : references) {
            if (ref == null || ref.getLength() <= 0) continue;
            raw.add(ReviewRange.builder().start(ref.getReviewStart()).length(ref.getLength()).build());
        }

        return mergeOverlappingRanges(raw);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Format change range checks
    // ─────────────────────────────────────────────────────────────────────────

    public static boolean formatChangeCoversRange(
            FormatChangeItem item,
            int targetStart,
            int targetLength
    ) {
        if (item == null || targetLength <= 0) return false;
        int targetEnd = targetStart + targetLength;

        for (ReviewRange range : deriveMergedRangesFromReferences(item.getReferences())) {
            int refStart = range.getStart();
            int refEnd = refStart + range.getLength();

            if (refStart <= targetStart && refEnd >= targetEnd) {
                return true;
            }
        }

        return false;
    }

    public static boolean formatChangeOverlapsRange(
            FormatChangeItem item,
            int targetStart,
            int targetEnd
    ) {
        if (item == null || targetEnd <= targetStart) return false;

        for (ReviewRange range : deriveMergedRangesFromReferences(item.getReferences())) {
            int refStart = range.getStart();
            int refEnd = refStart + range.getLength();

            if (targetStart < refEnd && targetEnd > refStart) {
                return true;
            }
        }

        return false;
    }

    public static boolean formatChangeTouchesOrOverlapsRange(
            FormatChangeItem item,
            int targetStart,
            int targetEnd
    ) {
        if (item == null || targetEnd <= targetStart) return false;

        for (ReviewRange range : deriveMergedRangesFromReferences(item.getReferences())) {
            int refStart = range.getStart();
            int refEnd = refStart + range.getLength();

            if (targetStart <= refEnd && targetEnd >= refStart) {
                return true;
            }
        }

        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Format change find/create (compatibility)
    // ─────────────────────────────────────────────────────────────────────────

    public static FormatChangeItem findOrCreateCompatibleFormatChange(
            List<FormatChangeItem> formatChanges,
            String actorEmail,
            String createdAt,
            String attrKey,
            Object attrValue,
            int rangeStart,
            int rangeEnd
    ) {
        List<FormatChangeItem> matches = formatChanges.stream()
                .filter(f -> actorEmail.equals(f.getActorEmail()))
                .filter(f -> attrKey.equals(f.getAttributeKey()))
                .filter(f -> Objects.equals(attrValue, f.getAttributeValue()))
                .filter(f -> formatChangeTouchesOrOverlapsRange(f, rangeStart, rangeEnd))
                .collect(Collectors.toList());

        if (matches.isEmpty()) {
            FormatChangeItem created = FormatChangeItem.builder()
                    .groupId(nextId())
                    .actorEmail(actorEmail)
                    .createdAt(createdAt)
                    .attributeKey(attrKey)
                    .attributeValue(attrValue)
                    .references(new ArrayList<>())
                    .previewText("")
                    .dependsOnInsertGroupIds(new ArrayList<>())
                    .dependsOnDeleteGroupIds(new ArrayList<>())
                    .build();

            formatChanges.add(created);
            return created;
        }

        FormatChangeItem primary = matches.get(0);

        for (int i = 1; i < matches.size(); i++) {
            FormatChangeItem other = matches.get(i);

            primary.setReferences(appendReferences(primary.getReferences(), other.getReferences()));

            for (String dep : other.getDependsOnInsertGroupIds()) {
                if (!primary.getDependsOnInsertGroupIds().contains(dep)) primary.getDependsOnInsertGroupIds().add(dep);
            }

            for (String dep : other.getDependsOnDeleteGroupIds()) {
                if (!primary.getDependsOnDeleteGroupIds().contains(dep)) primary.getDependsOnDeleteGroupIds().add(dep);
            }

            if (other.getCreatedAt().compareTo(primary.getCreatedAt()) > 0) primary.setCreatedAt(other.getCreatedAt());

            formatChanges.removeIf(f -> f.getGroupId().equals(other.getGroupId()));
        }
        return primary;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Format change inherit insert
    // ─────────────────────────────────────────────────────────────────────────

    public static boolean formatChangeShouldInheritInsert(
            FormatChangeItem group,
            int insertPos
    ) {
        if (group == null) return false;

        for (ReviewRange range : deriveMergedRangesFromReferences(group.getReferences())) {
            int rangeStart = range.getStart();
            int rangeEnd = rangeStart + range.getLength();
            if (insertPos >= rangeStart && insertPos <= rangeEnd) {
                return true;
            }
        }

        return false;
    }

    public static void extendFormatGroupForInheritedInsert(
            FormatChangeItem group,
            int insertPos,
            int insertLength,
            String opId,
            int compIdx,
            String currentInsertGroupId
    ) {
        if (group == null || insertLength <= 0) return;

        group.setReferences(shiftReferencesForInsert(group.getReferences(), insertPos, insertLength));

        group.setReferences(addReference(
                group.getReferences(), insertPos, 0, insertLength, opId, compIdx));

        if (!group.getDependsOnInsertGroupIds().contains(currentInsertGroupId)) {
            group.getDependsOnInsertGroupIds().add(currentInsertGroupId);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reference range manipulation
    // ─────────────────────────────────────────────────────────────────────────

    public static List<Reference> removeRangeFromReferencesWithoutShift(
            List<Reference> references,
            int removeStart,
            int removeLength
    ) {
        List<Reference> out = new ArrayList<>();

        if (references == null || references.isEmpty() || removeLength <= 0) {
            return cloneReferences(references);
        }

        int removeEnd = removeStart + removeLength;

        for (Reference reference : references) {
            if (reference == null) continue;

            int referenceStart = reference.getReviewStart();
            int referenceEnd = referenceStart + reference.getLength();

            if (referenceEnd <= removeStart || referenceStart >= removeEnd) {
                out = appendAndCoalesceReference(out, reference);
                continue;
            }

            int leftLen = Math.max(0, removeStart - referenceStart);
            int rightLen = Math.max(0, referenceEnd - removeEnd);

            if (leftLen > 0) {
                out = appendAndCoalesceReference(out,
                        Reference.builder()
                                .reviewStart(referenceStart)
                                .componentStart(reference.getComponentStart())
                                .length(leftLen)
                                .opId(reference.getOpId())
                                .componentIndex(reference.getComponentIndex())
                                .build());
            }

            if (rightLen > 0) {
                out = appendAndCoalesceReference(out,
                        Reference.builder()
                                .reviewStart(removeEnd)
                                .componentStart(reference.getComponentStart() + Math.max(0, removeEnd - referenceStart))
                                .length(rightLen)
                                .opId(reference.getOpId())
                                .componentIndex(reference.getComponentIndex())
                                .build());
            }
        }

        return out;
    }

    public static List<Reference> deleteRangeFromReferencesAndShift(
            List<Reference> references,
            int deleteStart,
            int deleteLength
    ) {
        List<Reference> out = new ArrayList<>();

        if (references == null || references.isEmpty() || deleteLength <= 0) {
            return cloneReferences(references);
        }

        int deleteEnd = deleteStart + deleteLength;

        for (Reference reference : references) {
            if (reference == null) continue;

            int referenceStart = reference.getReviewStart();
            int referenceEnd = referenceStart + reference.getLength();

            if (referenceEnd <= deleteStart) {
                out = appendAndCoalesceReference(out, reference);
                continue;
            }

            if (referenceStart >= deleteEnd) {
                out = appendAndCoalesceReference(out,
                        Reference.builder()
                                .reviewStart(referenceStart - deleteLength)
                                .componentStart(reference.getComponentStart())
                                .length(reference.getLength())
                                .opId(reference.getOpId())
                                .componentIndex(reference.getComponentIndex())
                                .build());
                continue;
            }

            int leftLen = Math.max(0, deleteStart - referenceStart);
            int rightLen = Math.max(0, referenceEnd - deleteEnd);

            if (leftLen > 0) {
                out = appendAndCoalesceReference(out,
                        Reference.builder()
                                .reviewStart(referenceStart)
                                .componentStart(reference.getComponentStart())
                                .length(leftLen)
                                .opId(reference.getOpId())
                                .componentIndex(reference.getComponentIndex())
                                .build());
            }

            if (rightLen > 0) {
                out = appendAndCoalesceReference(out,
                        Reference.builder()
                                .reviewStart(deleteStart)
                                .componentStart(reference.getComponentStart() + Math.max(0, deleteEnd - referenceStart))
                                .length(rightLen)
                                .opId(reference.getOpId())
                                .componentIndex(reference.getComponentIndex())
                                .build());
            }
        }

        return out;
    }

    public void shiftFormatChangeReferences(
            List<FormatChangeItem> formatChanges,
            int insertPos,
            int shiftLen,
            Set<String> excludedGroupIds
    ) {
        if (formatChanges == null || shiftLen <= 0) {
            return;
        }

        Set<String> excluded = excludedGroupIds != null ? excludedGroupIds : Collections.emptySet();

        for (FormatChangeItem fmt : formatChanges) {
            if (fmt == null || excluded.contains(fmt.getGroupId())) {
                continue;
            }

            List<Reference> before = cloneReferences(fmt.getReferences());

            fmt.setReferences(shiftReferencesForInsert(fmt.getReferences(), insertPos, shiftLen));

        }
    }

    public static List<Reference> collectReferencesForRunIndices(
            List<Segment> runs,
            List<Integer> indices
    ) {
        List<Reference> refs = new ArrayList<>();
        if (runs == null || indices == null) return refs;

        for (Integer idx : indices) {
            if (idx == null || idx < 0 || idx >= runs.size()) {
                continue;
            }
            Segment run = runs.get(idx);
            refs = appendReferences(refs, run.getReferences());
        }

        return refs;
    }

    public static void deleteRangeFromRunReferencesAndShift(
            List<Segment> runs,
            int deleteStart,
            int deleteLength
    ) {
        if (runs == null || runs.isEmpty() || deleteLength <= 0) return;

        int count = 0;
        for (Segment run : runs) {
            if (run.getReferences() == null || run.getReferences().isEmpty()) continue;
            List<Reference> before = cloneReferences(run.getReferences());
            run.setReferences(deleteRangeFromReferencesAndShift(run.getReferences(), deleteStart, deleteLength));
            if (!run.getReferences().equals(before)) {
                count++;
            }
        }
    }

    public static void deleteRangeFromFormatChangeReferencesAndShift(
            Collection<FormatChangeItem> formatChanges,
            int deleteStart,
            int deleteLength
    ) {
        if (formatChanges == null || formatChanges.isEmpty() || deleteLength <= 0) return;

        for (FormatChangeItem fmt : formatChanges) {
            if (fmt.getReferences() == null || fmt.getReferences().isEmpty()) continue;

            List<Reference> before = cloneReferences(fmt.getReferences());
            fmt.setReferences(deleteRangeFromReferencesAndShift(fmt.getReferences(), deleteStart, deleteLength));
        }
    }

    public static List<Reference> shiftReferencesForInsert(
            List<Reference> references,
            int insertPos,
            int insertLength
    ) {
        List<Reference> out = new ArrayList<>();

        if (references == null || references.isEmpty() || insertLength <= 0) {
            return cloneReferences(references);
        }

        for (Reference reference : references) {
            if (reference == null) continue;

            int referenceStart = reference.getReviewStart();
            int referenceEnd = referenceStart + reference.getLength();

            if (referenceEnd <= insertPos) {
                out = appendAndCoalesceReference(out, reference);
                continue;
            }

            if (referenceStart >= insertPos) {
                out = appendAndCoalesceReference(out,
                        Reference.builder()
                                .reviewStart(referenceStart + insertLength)
                                .componentStart(reference.getComponentStart())
                                .length(reference.getLength())
                                .opId(reference.getOpId())
                                .componentIndex(reference.getComponentIndex())
                                .build());
                continue;
            }

            int leftLen = insertPos - referenceStart;
            int rightLen = referenceEnd - insertPos;

            if (leftLen > 0) {
                out = appendAndCoalesceReference(out,
                        Reference.builder()
                                .reviewStart(referenceStart)
                                .componentStart(reference.getComponentStart())
                                .length(leftLen)
                                .opId(reference.getOpId())
                                .componentIndex(reference.getComponentIndex())
                                .build());
            }

            if (rightLen > 0) {
                out = appendAndCoalesceReference(out,
                        Reference.builder()
                                .reviewStart(insertPos + insertLength)
                                .componentStart(reference.getComponentStart() + leftLen)
                                .length(rightLen)
                                .opId(reference.getOpId())
                                .componentIndex(reference.getComponentIndex())
                                .build());
            }
        }

        return out;
    }

    public static DeleteChange.DeleteChangeType promotedDeleteType(
            DeleteChange.DeleteChangeType current,
            boolean deletingNewline
    ) {
        DeleteChange.DeleteChangeType safeCurrent =
                current != null ? current : DeleteChange.DeleteChangeType.TEXT;

        if (!deletingNewline) {
            return safeCurrent;
        }

        if (safeCurrent == DeleteChange.DeleteChangeType.TEXT) {
            return DeleteChange.DeleteChangeType.SINGLE_LINE;
        }

        return DeleteChange.DeleteChangeType.MULTI_LINE;
    }

    public static void applyDeleteTypeToGroupRuns(
            List<Segment> runs,
            String groupId,
            DeleteChange.DeleteChangeType type
    ) {
        for (Segment run : runs) {
            if (run.getDeleteChange() == null) continue;
            if (!groupId.equals(run.getDeleteChange().getGroupId())) continue;

            run.getDeleteChange().setType(type);
        }
    }

    public static Map<String, Object> cloneEmbed(Object embed) {
        if (embed instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) embed;
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return out;
        }

        return new LinkedHashMap<>();
    }

    public static String reviewRunIdForBase(int logicalStart) {
        return "base_" + logicalStart;
    }

    public static String reviewRunIdForReference(
            String opId,
            int componentIndex,
            int componentStart,
            int reviewStart
    ) {
        return "run_" + opId + "_" + componentIndex + "_" + componentStart + "_" + reviewStart;
    }

    public static boolean isNewlineRun(Segment run) {
        return run != null && run.isText() && "\n".equals(run.getText());
    }

    public static boolean isMeaningfulLineContentRun(Segment run) {
        if (run == null) return false;
        if (run.getDeleteChange() != null) return true;
        if (run.isEmbed()) return true;
        return run.isText() && !"\n".equals(run.getText()) && !run.getText().isEmpty();
    }

    public static List<String> collectLineDependencyRunIdsForNewline(
            List<Segment> runs,
            int newlineIndex
    ) {
        List<String> deps = new ArrayList<>();

        if (runs == null || newlineIndex < 0 || newlineIndex >= runs.size()) {
            return deps;
        }

        for (int i = newlineIndex - 1; i >= 0; i--) {
            Segment run = runs.get(i);

            if (isNewlineRun(run)) {
                break;
            }

            if (!isMeaningfulLineContentRun(run)) {
                continue;
            }

            String dependencyId = logicalLineDependencyId(run);

            if (dependencyId == null || Helpers.isBlank(dependencyId)) {
                continue;
            }

            if (!deps.contains(dependencyId)) {
                deps.add(0, dependencyId);
            }
        }

        return deps;
    }

    public static String logicalLineDependencyId(Segment run) {
        if (run == null) return null;

        if (run.getInsertChange() != null) {
            return "insert:" + run.getInsertChange().getGroupId();
        }

        if (run.getDeleteChange() != null) {
            return "delete:" + run.getDeleteChange().getGroupId();
        }

        if (run.getId() != null && !Helpers.isBlank(run.getId())) {
            return "run:" + run.getId();
        }

        return null;
    }

    public static List<InsertFragment> buildInsertFragments(Object insertValue) {
        List<InsertFragment> fragments = new ArrayList<>();

        if (insertValue instanceof String) {
            String text = (String) insertValue;
            int componentCursor = 0;
            String[] parts = text.split("\n", -1);

            for (int i = 0; i < parts.length; i++) {
                if (!parts[i].isEmpty()) {
                    fragments.add(
                            new InsertFragment(
                                    parts[i],
                                    null,
                                    parts[i].length(),
                                    componentCursor,
                                    false
                            )
                    );

                    componentCursor += parts[i].length();
                }

                if (i < parts.length - 1) {
                    fragments.add(
                            new InsertFragment(
                                    "\n",
                                    null,
                                    1,
                                    componentCursor,
                                    true
                            )
                    );

                    componentCursor += 1;
                }
            }

            return fragments;
        }

        if (insertValue instanceof Map<?, ?>) {
            Map<?, ?> embed = (Map<?, ?>) insertValue;
            fragments.add(
                    new InsertFragment(
                            null,
                            cloneEmbed(embed),
                            1,
                            0,
                            false
                    )
            );
        }

        return fragments;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public class InsertFragment {
            private String text;
            private Object embed;
            private int length;
            private int componentStart;
            private boolean newline;

        public boolean isEmbed() {
            return embed != null;
        }
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public class BlockGroupKey {
        private String attributeKey;
        private Object attributeValue;
    }

    private static final Set<String> BLOCK_ATTR_KEYS = new HashSet<>(Arrays.asList(
            "header",
            "list",
            "indent",
            "align",
            "blockquote",
            "code-block",
            "direction"
    ));

    private static boolean isBlockAttribute(String key) {
        return key != null && BLOCK_ATTR_KEYS.contains(key);
    }

    public static Map<String, Object> onlyInlineAttrs(Map<String, Object> attrs) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (attrs == null) return out;

        for (Map.Entry<String, Object> entry : attrs.entrySet()) {
            if (!isBlockAttribute(entry.getKey())) {
                out.put(entry.getKey(), entry.getValue());
            }
        }

        return out;
    }

    public static Map<String, Object> onlyBlockAttrs(Map<String, Object> attrs) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (attrs == null) return out;

        for (Map.Entry<String, Object> entry : attrs.entrySet()) {
            if (isBlockAttribute(entry.getKey())) {
                out.put(entry.getKey(), entry.getValue());
            }
        }

        return out;
    }

    public static boolean isBlockTargetRun(Segment run) {
        return run != null && run.isText() && "\n".equals(run.getText());
    }

    private static BlockFormatBehavior blockBehaviorFor(String key) {
        if ("list".equals(key) || "blockquote".equals(key) || "code-block".equals(key)) {
            return BlockFormatBehavior.CONTINUING;
        }

        if ("header".equals(key)) {
            return BlockFormatBehavior.NON_CONTINUING;
        }

        return BlockFormatBehavior.COEXISTING;
    }

    private static BlockFormatConflictGroup blockConflictGroupFor(String key) {
        if ("align".equals(key)) return BlockFormatConflictGroup.ALIGNMENT;
        if ("indent".equals(key)) return BlockFormatConflictGroup.INDENT;
        if ("direction".equals(key)) return BlockFormatConflictGroup.DIRECTION;
        return BlockFormatConflictGroup.EXCLUSIVE_BLOCK_STYLE;
    }

    public static boolean blockChangeOverlapsRange(
            BlockFormatChangeItem item,
            int targetStart,
            int targetEnd
    ) {
        if (item == null || targetEnd <= targetStart) return false;

        for (ReviewRange range : deriveMergedRangesFromReferences(item.getReferences())) {
            int refStart = range.getStart();
            int refEnd = refStart + range.getLength();

            if (targetStart < refEnd && targetEnd > refStart) {
                return true;
            }
        }

        return false;
    }

    private static boolean shouldCancelBlockChange(
            BlockFormatChangeItem existing,
            String incomingKey,
            Object incomingValue
    ) {
        if (existing == null) return false;

        String existingKey = existing.getAttributeKey();

        if (Objects.equals(existingKey, incomingKey)) {
            return true;
        }

        if (incomingValue == null) {
            return false;
        }

        return existing.getConflictGroup() == BlockFormatConflictGroup.EXCLUSIVE_BLOCK_STYLE
                && blockConflictGroupFor(incomingKey) == BlockFormatConflictGroup.EXCLUSIVE_BLOCK_STYLE;
    }

    private BlockFormatChangeItem findOrCreateCurrentBlockGroup(
            List<Segment> runs,
            List<BlockFormatChangeItem> blockFormatChanges,
            Map<BlockGroupKey, BlockFormatChangeItem> currentBlockGroups,
            String actorEmail,
            String createdAt,
            String attrKey,
            Object attrValue,
            int spanStart
    ) {
        BlockGroupKey key = new BlockGroupKey(attrKey, attrValue);
        BlockFormatBehavior behavior = blockBehaviorFor(attrKey);

        BlockFormatChangeItem existingInOp = currentBlockGroups.get(key);

        if (existingInOp != null) {
            if (behavior != BlockFormatBehavior.CONTINUING
                    || isContinuingBlockChangeAdjacent(
                    runs,
                    existingInOp,
                    spanStart
            )) {
                return existingInOp;
            }

            currentBlockGroups.remove(key);
        }

        if (behavior == BlockFormatBehavior.CONTINUING) {
            BlockFormatChangeItem adjacent = blockFormatChanges.stream()
                    .filter(f -> attrKey.equals(f.getAttributeKey()))
                    .filter(f -> Objects.equals(attrValue, f.getAttributeValue()))
                    .filter(f -> f.getBehavior() == BlockFormatBehavior.CONTINUING)
                    .filter(f -> isContinuingBlockChangeAdjacent(
                            runs,
                            f,
                            spanStart
                    ))
                    .findFirst()
                    .orElse(null);

            if (adjacent != null) {
                currentBlockGroups.put(key, adjacent);
                return adjacent;
            }
        }

        BlockFormatChangeItem created = BlockFormatChangeItem.builder()
                .groupId(nextId())
                .actorEmail(actorEmail)
                .createdAt(createdAt)
                .attributeKey(attrKey)
                .attributeValue(attrValue)
                .behavior(behavior)
                .conflictGroup(blockConflictGroupFor(attrKey))
                .references(new ArrayList<>())
                .previewText("")
                .dependsOnInsertGroupIds(new ArrayList<>())
                .dependsOnDeleteGroupIds(new ArrayList<>())
                .build();

        blockFormatChanges.add(created);
        currentBlockGroups.put(key, created);

        return created;
    }

    /**
     * A CONTINUING block change is "adjacent" to a new newline at spanStart if
     * any of its references ends exactly at spanStart (i.e. the previous newline
     * was at spanStart-1 and its reference covers [spanStart-1, 1]).
     * We also allow a gap of exactly one character to handle the case where the
     * incoming retain component straddles a non-newline run between two newlines
     * (e.g. list items with content between them).
     * More precisely: the merged reference range ends at or reaches spanStart.
     */
    private static boolean isContinuingBlockChangeAdjacent(
            List<Segment> runs,
            BlockFormatChangeItem item,
            int spanStart
    ) {
        if (runs == null || item == null || item.getReferences() == null) {
            return false;
        }

        Reference nearestPreviousRef = null;

        for (Reference ref : item.getReferences()) {
            if (ref == null) continue;

            int refStart = ref.getReviewStart();

            if (refStart >= spanStart) continue;

            if (nearestPreviousRef == null
                    || refStart > nearestPreviousRef.getReviewStart()) {
                nearestPreviousRef = ref;
            }
        }

        if (nearestPreviousRef == null) {
            return false;
        }

        return areContinuingBlockTargetsConnected(
                runs,
                nearestPreviousRef.getReviewStart(),
                spanStart
        );
    }

    private static void addBlockInsertDependency(
            BlockFormatChangeItem item,
            String insertGroupId
    ) {
        if (item == null || insertGroupId == null || Helpers.isBlank(insertGroupId)) return;

        if (!item.getDependsOnInsertGroupIds().contains(insertGroupId)) {
            item.getDependsOnInsertGroupIds().add(insertGroupId);
        }
    }

    public void addBlockDeleteDependency(
            BlockFormatChangeItem item,
            String deleteGroupId
    ) {
        if (item == null || deleteGroupId == null) return;

        if (!item.getDependsOnDeleteGroupIds().contains(deleteGroupId)) {
            item.getDependsOnDeleteGroupIds().add(deleteGroupId);
        }
    }

    public void applyBlockAttributeToNewlineRun(
            List<Segment> runs,
            List<BlockFormatChangeItem> blockFormatChanges,
            AttributionCancellationAccumulator accumulator,
            Segment target,
            String attrKey,
            Object attrValue,
            String authorEmail,
            String createdAt,
            String opId,
            int compIdx,
            int componentStart,
            Map<BlockGroupKey, BlockFormatChangeItem> currentBlockGroups
    ) {
        if (!isBlockTargetRun(target)) return;

        int spanStart = target.getLogicalStart();
        int spanLen = 1;
        int spanEnd = spanStart + spanLen;

        Object baseValue = target.getBaseAttributes() != null
                ? target.getBaseAttributes().get(attrKey)
                : null;

        List<BlockFormatChangeItem> overlappingBlockChanges =
                blockFormatChanges.stream()
                        .filter(f -> blockChangeOverlapsRange(f, spanStart, spanEnd))
                        .collect(Collectors.toList());

        for (BlockFormatChangeItem existing : new ArrayList<>(overlappingBlockChanges)) {
            if (!shouldCancelBlockChange(existing, attrKey, attrValue)) {
                continue;
            }

            for (Reference reference : existing.getReferences()) {
                int referenceStart = reference.getReviewStart();
                int referenceEnd = referenceStart + reference.getLength();

                int overlapStart = Math.max(spanStart, referenceStart);
                int overlapEnd = Math.min(spanEnd, referenceEnd);

                if (overlapStart >= overlapEnd) continue;

                int sourceStart = reference.getComponentStart() + (overlapStart - referenceStart);
                int sourceLen = overlapEnd - overlapStart;

                accumulator.recordFormatCancellation(
                        reference.getOpId(),
                        reference.getComponentIndex(),
                        sourceStart,
                        sourceLen,
                        existing.getAttributeKey()
                );
            }

            existing.setReferences(
                    removeRangeFromReferencesWithoutShift(
                            existing.getReferences(),
                            spanStart,
                            spanLen
                    )
            );

            if (existing.getReferences().isEmpty()) {
                blockFormatChanges.remove(existing);
            }

            if (target.getChangeAttributes() != null) {
                target.getChangeAttributes().remove(existing.getAttributeKey());
            }
        }

        if (attrValue == null) {
            if (target.getChangeAttributes() != null) {
                target.getChangeAttributes().remove(attrKey);
            }
            return;
        }

        if (Objects.equals(baseValue, attrValue)) {
            return;
        }

        target.setChangeAttributes(
                overlayAttrsPreserveNull(
                        target.getChangeAttributes(),
                        Collections.singletonMap(attrKey, attrValue)
                )
        );

        BlockFormatChangeItem blockGroup = findOrCreateCurrentBlockGroup(
                runs,
                blockFormatChanges,
                currentBlockGroups,
                authorEmail,
                createdAt,
                attrKey,
                attrValue,
                spanStart
        );

        blockGroup.setReferences(
                addReference(
                        blockGroup.getReferences(),
                        spanStart,
                        componentStart,
                        spanLen,
                        opId,
                        compIdx
                )
        );
    }

    public void cancelBlockChangesForDeletedNewline(
            List<BlockFormatChangeItem> blockFormatChanges,
            AttributionCancellationAccumulator accumulator,
            Segment deletedRun
    ) {
        if (!isBlockTargetRun(deletedRun)) return;

        int deleteStart = deletedRun.getLogicalStart();
        int deleteEnd = deleteStart + 1;

        for (BlockFormatChangeItem item : new ArrayList<>(blockFormatChanges)) {
            if (!blockChangeOverlapsRange(item, deleteStart, deleteEnd)) continue;

            for (Reference reference : item.getReferences()) {
                int refStart = reference.getReviewStart();
                int refEnd = refStart + reference.getLength();

                int overlapStart = Math.max(deleteStart, refStart);
                int overlapEnd = Math.min(deleteEnd, refEnd);

                if (overlapStart >= overlapEnd) continue;

                accumulator.recordFormatCancellation(
                        reference.getOpId(),
                        reference.getComponentIndex(),
                        reference.getComponentStart() + (overlapStart - refStart),
                        overlapEnd - overlapStart,
                        item.getAttributeKey()
                );
            }

            item.setReferences(
                    removeRangeFromReferencesWithoutShift(
                            item.getReferences(),
                            deleteStart,
                            1
                    )
            );

            if (item.getReferences().isEmpty()) {
                blockFormatChanges.remove(item);
            }
        }
    }

    public void shiftBlockFormatChangeReferences(
            List<BlockFormatChangeItem> items,
            int insertPos,
            int shiftLen,
            Set<String> excludedGroupIds
    ) {
        if (items == null || shiftLen <= 0) return;

        Set<String> excluded = excludedGroupIds != null
                ? excludedGroupIds
                : Collections.emptySet();

        for (BlockFormatChangeItem item : items) {
            if (excluded.contains(item.getGroupId())) continue;

            for (Reference ref : item.getReferences()) {
                if (ref.getReviewStart() >= insertPos) {
                    ref.setReviewStart(ref.getReviewStart() + shiftLen);
                }
            }
        }
    }

    public void deleteRangeFromBlockFormatChangeReferencesAndShift(
            List<BlockFormatChangeItem> items,
            int deleteStart,
            int deleteLen
    ) {
        if (items == null || deleteLen <= 0) return;

        for (BlockFormatChangeItem item : items) {
            item.setReferences(
                    deleteRangeFromReferencesAndShift(
                            item.getReferences(),
                            deleteStart,
                            deleteLen
                    )
            );
        }

        items.removeIf(item -> item.getReferences() == null || item.getReferences().isEmpty());
    }

    public String getLinePreviewForNewline(List<Segment> runs, int newlinePos) {
        StringBuilder line = new StringBuilder();

        for (Segment run : runs) {
            if (run.getDeleteChange() != null) continue;

            int start = run.getLogicalStart();
            int end = start + run.length();

            if (end <= newlinePos) {
                if (run.isText() && "\n".equals(run.getText())) {
                    line.setLength(0);
                } else if (run.isEmbed()) {
                    line.append("[image]");
                } else if (run.isText()) {
                    line.append(run.getText());
                }

                continue;
            }

            if (start <= newlinePos) {
                if (run.isEmbed()) {
                    line.append("[image]");
                } else if (run.isText()) {
                    int offset = newlinePos - start;
                    line.append(run.getText(), 0, Math.max(0, offset));
                }

                break;
            }
        }

        String out = line.toString().trim();
        return Helpers.isBlank(out) ? "[empty line]" : out;
    }

    public static Segment effectivePreviousRunForInsertGrouping(
            List<Segment> runs,
            int insertAtIdx
    ) {
        if (runs == null || insertAtIdx <= 0) {
            return null;
        }

        for (int i = insertAtIdx - 1; i >= 0; i--) {
            Segment run = runs.get(i);

            if (isNewlineRun(run) && run.getInsertChange() != null) {
                continue;
            }

            return run;
        }

        return null;
    }

    public static Segment effectiveNextRunForInsertGrouping(
            List<Segment> runs,
            int insertAtIdx
    ) {
        if (runs == null || insertAtIdx < 0 || insertAtIdx >= runs.size()) {
            return null;
        }

        for (int i = insertAtIdx; i < runs.size(); i++) {
            Segment run = runs.get(i);

            if (isNewlineRun(run) && run.getInsertChange() != null) {
                continue;
            }

            return run;
        }

        return null;
    }

    public static Segment effectivePreviousRunForDeleteGrouping(
            List<Segment> runs,
            int deleteAtIdx
    ) {
        if (runs == null || deleteAtIdx <= 0) {
            return null;
        }

        for (int i = deleteAtIdx - 1; i >= 0; i--) {
            Segment run = runs.get(i);

            if (isNewlineRun(run) && run.getInsertChange() != null) {
                continue;
            }

            return run;
        }

        return null;
    }

    public static Segment effectiveNextRunForDeleteGrouping(
            List<Segment> runs,
            int deleteAtIdx
    ) {
        if (runs == null || deleteAtIdx < 0 || deleteAtIdx >= runs.size()) {
            return null;
        }

        for (int i = deleteAtIdx; i < runs.size(); i++) {
            Segment run = runs.get(i);

            if (isNewlineRun(run) && run.getInsertChange() != null) {
                continue;
            }

            return run;
        }

        return null;
    }

    public static void syncBlockFormatDependenciesFromTargetNewlines(
            List<Segment> runs,
            List<BlockFormatChangeItem> blockFormatChanges
    ) {
        if (runs == null || runs.isEmpty()) return;
        if (blockFormatChanges == null || blockFormatChanges.isEmpty()) return;

        for (BlockFormatChangeItem item : blockFormatChanges) {
            if (item == null || item.getReferences() == null) continue;

            for (Reference ref : item.getReferences()) {
                if (ref == null) continue;

                int newlineIndex = findRunIndexAtReviewStart(
                        runs,
                        ref.getReviewStart()
                );

                if (newlineIndex < 0) continue;

                Segment target = runs.get(newlineIndex);

                if (!isNewlineRun(target)) continue;

                addBlockDependenciesFromTargetNewline(
                        item,
                        runs,
                        newlineIndex
                );
            }
        }
    }

    public static void addBlockDependenciesFromTargetNewline(
            BlockFormatChangeItem item,
            List<Segment> runs,
            int newlineIndex
    ) {
        if (item == null || runs == null) return;
        if (newlineIndex < 0 || newlineIndex >= runs.size()) return;

        List<String> deps = collectLineDependencyRunIdsForNewline(
                runs,
                newlineIndex
        );

        for (String dep : deps) {
            if (dep == null) continue;

            if (dep.startsWith("insert:")) {
                addBlockInsertDependency(
                        item,
                        dep.substring("insert:".length())
                );
            }

            if (dep.startsWith("delete:")) {
                addBlockDeleteDependency(
                        item,
                        dep.substring("delete:".length())
                );
            }
        }
    }

    public static int findRunIndexAtReviewStart(
            List<Segment> runs,
            int reviewStart
    ) {
        if (runs == null) return -1;

        for (int i = 0; i < runs.size(); i++) {
            Segment run = runs.get(i);
            if (run == null) continue;

            int start = run.getLogicalStart();
            int end = start + run.length();

            if (reviewStart >= start && reviewStart < end) {
                return i;
            }
        }

        return -1;
    }

    private static boolean areContinuingBlockTargetsConnected(
            List<Segment> runs,
            int previousNewlineStart,
            int currentNewlineStart
    ) {
        if (runs == null || runs.isEmpty()) return false;
        if (currentNewlineStart <= previousNewlineStart) return false;

        int previousIdx = findRunIndexAtReviewStart(runs, previousNewlineStart);
        int currentIdx = findRunIndexAtReviewStart(runs, currentNewlineStart);

        if (previousIdx < 0 || currentIdx < 0) return false;
        if (previousIdx >= currentIdx) return false;

        Segment previous = runs.get(previousIdx);
        Segment current = runs.get(currentIdx);

        if (!isNewlineRun(previous) || !isNewlineRun(current)) {
            return false;
        }

        /*
         * Two block-format newline targets are connected only if there is no
         * other newline between them.
         *
         * Allowed:
         *   [list newline] text [list newline]
         *
         * Not allowed:
         *   [list newline] [plain empty newline] text [list newline]
         */
        for (int i = previousIdx + 1; i < currentIdx; i++) {
            if (isNewlineRun(runs.get(i))) {
                return false;
            }
        }

        return true;
    }

    public static void normalizeContinuingBlockFormatGroups(
            List<Segment> runs,
            List<BlockFormatChangeItem> blockFormatChanges
    ) {
        if (runs == null || runs.isEmpty()) return;
        if (blockFormatChanges == null || blockFormatChanges.isEmpty()) return;

        List<BlockFormatChangeItem> keep = new ArrayList<>();
        Map<BlockGroupKey, List<BlockReferenceOwner>> continuingRefs = new LinkedHashMap<>();

        for (BlockFormatChangeItem item : blockFormatChanges) {
            if (item == null) continue;

            if (item.getBehavior() != BlockFormatBehavior.CONTINUING) {
                keep.add(item);
                continue;
            }

            BlockGroupKey key = new BlockGroupKey(
                    item.getAttributeKey(),
                    item.getAttributeValue()
            );

            for (Reference ref : item.getReferences()) {
                if (ref == null) continue;

                int idx = findRunIndexAtReviewStart(runs, ref.getReviewStart());
                if (idx < 0) continue;
                if (!isNewlineRun(runs.get(idx))) continue;

                continuingRefs
                        .computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(new BlockReferenceOwner(item, ref));
            }
        }

        for (Map.Entry<BlockGroupKey, List<BlockReferenceOwner>> entry : continuingRefs.entrySet()) {
            List<BlockReferenceOwner> refs = entry.getValue();

            refs.sort(Comparator.comparingInt(o -> o.getReference().getReviewStart()));

            List<List<BlockReferenceOwner>> chains = new ArrayList<>();
            List<BlockReferenceOwner> currentChain = new ArrayList<>();

            for (BlockReferenceOwner owner : refs) {
                if (currentChain.isEmpty()) {
                    currentChain.add(owner);
                    continue;
                }

                BlockReferenceOwner previous =
                        currentChain.get(currentChain.size() - 1);

                boolean connected = areContinuingBlockTargetsConnected(
                        runs,
                        previous.getReference().getReviewStart(),
                        owner.getReference().getReviewStart()
                );

                if (connected) {
                    currentChain.add(owner);
                } else {
                    chains.add(currentChain);
                    currentChain = new ArrayList<>();
                    currentChain.add(owner);
                }
            }

            if (!currentChain.isEmpty()) {
                chains.add(currentChain);
            }

            for (int i = 0; i < chains.size(); i++) {
                List<BlockReferenceOwner> chain = chains.get(i);
                if (chain.isEmpty()) continue;

                BlockFormatChangeItem source = chain.get(0).getOwner();

                BlockFormatChangeItem normalized =
                        i == 0
                                ? source
                                : BlockFormatChangeItem.builder()
                                .groupId(nextId())
                                .actorEmail(source.getActorEmail())
                                .createdAt(source.getCreatedAt())
                                .attributeKey(source.getAttributeKey())
                                .attributeValue(source.getAttributeValue())
                                .behavior(source.getBehavior())
                                .conflictGroup(source.getConflictGroup())
                                .references(new ArrayList<>())
                                .previewText("")
                                .dependsOnInsertGroupIds(new ArrayList<>())
                                .dependsOnDeleteGroupIds(new ArrayList<>())
                                .build();

                normalized.setReferences(new ArrayList<>());
                normalized.setDependsOnInsertGroupIds(new ArrayList<>());
                normalized.setDependsOnDeleteGroupIds(new ArrayList<>());

                for (BlockReferenceOwner owner : chain) {
                    normalized.setReferences(
                            appendAndCoalesceReference(
                                    normalized.getReferences(),
                                    owner.getReference()
                            )
                    );

                    BlockFormatChangeItem original = owner.getOwner();

                    if (original.getCreatedAt() != null
                            && normalized.getCreatedAt() != null
                            && original.getCreatedAt().compareTo(normalized.getCreatedAt()) < 0) {
                        normalized.setCreatedAt(original.getCreatedAt());
                        normalized.setActorEmail(original.getActorEmail());
                    }
                }

                keep.add(normalized);
            }
        }

        blockFormatChanges.clear();
        blockFormatChanges.addAll(keep);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Records
    // ─────────────────────────────────────────────────────────────────────────
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public class InsertGroupCollection {
        private List<Integer> indices;
        private int start;
        private int end;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public class ReferenceSplit {
        private List<Reference> left;
        private List<Reference> right;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    private class BlockReferenceOwner {
        private BlockFormatChangeItem owner;
        private Reference reference;
    }
}