package com.example.notes.utils;

import com.example.notes.dto.attribution.*;
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

    // ─────────────────────────────────────────────────────────────────────────
    // Group counter
    // ─────────────────────────────────────────────────────────────────────────

    public static void resetGroupCounter() {
        log.debug("[HELPER:CTR] resetGroupCounter: was {}", groupCtr);
        groupCtr = 0;
    }

    public static String nextId() {
        String id = "g_" + (++groupCtr);
        log.debug("[HELPER:CTR] nextId => {}", id);
        return id;
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
            log.warn("[HELPER:ATTRS] attrsEq JSON error, falling back to equals: {}", e.getMessage());
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

        log.debug("[HELPER:ATTRS] intersectAttrs candidate={} reference={} => {}", candidate, reference, out);
        return out;
    }

    public static Map<String, Object> subtractAttrs(
            Map<String, Object> attrs,
            Map<String, Object> remove
    ) {
        Map<String, Object> out = new LinkedHashMap<>(attrs != null ? attrs : Collections.emptyMap());
        if (remove != null) remove.keySet().forEach(out::remove);

        log.debug("[HELPER:ATTRS] subtractAttrs attrs={} remove={} => {}", attrs, remove, out);
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

    public static Map<String, Object> getEffectiveAttrs(ReviewRun run) {
        if (run == null) return Collections.emptyMap();

        Map<String, Object> out = new LinkedHashMap<>(
                run.getBaseAttributes() != null ? run.getBaseAttributes() : Collections.emptyMap());
        out.putAll(run.getSuggestionAttributes());
        return out;
    }

    public static String attrsToJson(Map<String, Object> attrs) {
        if (attrs == null || attrs.isEmpty()) return "{}";
        try {
            return MAPPER.writeValueAsString(new TreeMap<>(attrs));
        } catch (Exception e) {
            return "{}";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Run position
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Locate a logical document position inside the ReviewRun list.
     *
     * logicalPos ignores deleted-suggestion runs because deleted runs are visible
     * in review mode but do not count as live document text.
     *
     * absPos tracks the visual/runtime position, including deleted runs.
     */
    public static RunPosition findRunPos(List<ReviewRun> runs, int logicalPos) {
        int pos = 0;
        int absPos = 0;

        if (logicalPos < 0) {
            log.error("[HELPER:POS] findRunPos called with negative logicalPos={}", logicalPos);
        }

        for (int i = 0; i < runs.size(); i++) {
            ReviewRun r = runs.get(i);

            int runLen = r.length();

            if (r.getDeleteSuggestion() != null) {
                absPos += runLen;
                continue;
            }

            if (pos == logicalPos) {
                log.debug("[HELPER:POS] findRunPos(logicalPos={}) => idx={} offset=0 absPos={}", logicalPos, i, absPos);
                return new RunPosition(i, 0, absPos);
            }

            if (pos + runLen > logicalPos) {
                int off = logicalPos - pos;
                return new RunPosition(i, off, absPos + off);
            }

            pos += runLen;
            absPos += runLen;
        }

        // past end of document
        log.debug("[HELPER:POS] findRunPos(logicalPos={}) => past end idx={} absPos={} (docLen={})",
                logicalPos, runs.size(), absPos, pos);

        if (logicalPos > pos) {
            log.warn("[HELPER:POS:WARN] findRunPos logicalPos={} > computed docLen={} — position is beyond document end",
                    logicalPos, pos);
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
    public static int splitAt(List<ReviewRun> runs, int idx, int offset) {
        if (idx >= runs.size()) {
            log.error("[HELPER:SPLIT] splitAt idx={} >= runs.size()={} — out of bounds, no split", idx, runs.size());
            return idx;
        }

        ReviewRun r = runs.get(idx);

        if (r.isEmbed()) {
            return idx;
        }

        if (offset <= 0 || offset >= r.length()) {
            log.debug("[HELPER:SPLIT] splitAt idx={} offset={} — no split needed (text.len={})",
                    idx, offset, r.length());
            return idx;
        }

        int splitAbsPos = r.getLogicalStart() + offset;

        log.debug("[HELPER:SPLIT] splitAt idx={} offset={} text='{}' logicalStart={} splitAbsPos={}",
                idx, offset, runTextForLog(r), r.getLogicalStart(), splitAbsPos);

        SuggestionReferenceSplit split = splitSuggestionReferences(r.getReferences(), splitAbsPos);

        ReviewRun left = ReviewRun.builder()
                .text(r.getText().substring(0, offset))
                .baseAttributes(new LinkedHashMap<>(r.getBaseAttributes() != null ? r.getBaseAttributes() : Collections.emptyMap()))
                .suggestionAttributes(new LinkedHashMap<>(r.getSuggestionAttributes() != null ? r.getSuggestionAttributes() : Collections.emptyMap()))
                .references(split.left())
                .logicalStart(r.getLogicalStart())
                .insertSuggestion(copyInsertSuggestion(r.getInsertSuggestion()))
                .deleteSuggestion(copyDeleteSuggestion(r.getDeleteSuggestion()))
                .build();

        ReviewRun right = ReviewRun.builder()
                .text(r.getText().substring(offset))
                .baseAttributes(new LinkedHashMap<>(r.getBaseAttributes() != null ? r.getBaseAttributes() : Collections.emptyMap()))
                .suggestionAttributes(new LinkedHashMap<>(r.getSuggestionAttributes() != null ? r.getSuggestionAttributes() : Collections.emptyMap()))
                .references(split.right())
                .logicalStart(splitAbsPos)
                .insertSuggestion(copyInsertSuggestion(r.getInsertSuggestion()))
                .deleteSuggestion(copyDeleteSuggestion(r.getDeleteSuggestion()))
                .build();

        runs.set(idx, left);
        runs.add(idx + 1, right);

        log.debug("[HELPER:SPLIT] split result left='{}' logicalStart={} right='{}' logicalStart={}",
                runTextForLog(left), left.getLogicalStart(),
                runTextForLog(right), right.getLogicalStart());

        return idx + 1;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Newline-only retain check
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
            int lenToCheck = Math.min(run.length() - offset, remaining);

            if (run.isEmbed()) return false;
            for (int j = offset; j < offset + lenToCheck; j++) {
                if (run.getText().charAt(j) != '\n') {
                    log.debug("[HELPER:NL] isOnlyNewlineRetain logicalStart={} retainLength={} => false (non-newline at run {})",
                            logicalStart, retainLength, i);
                    return false;
                }
            }

            remaining -= lenToCheck;
            offset = 0;
        }

        boolean result = sawOverlap;
        log.debug("[HELPER:NL] isOnlyNewlineRetain logicalStart={} retainLength={} => {}", logicalStart, retainLength, result);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Format range helpers
    // ─────────────────────────────────────────────────────────────────────────


    public static List<ReviewRange> mergeOverlappingRanges(List<ReviewRange> ranges) {
        if (ranges == null || ranges.isEmpty()) return new ArrayList<>();

        List<ReviewRange> sorted = ranges.stream()
                .sorted(Comparator.comparingInt(ReviewRange::getStart))
                .toList();

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
                    || !run.getInsertSuggestion().getGroupId().equals(groupId)) continue;

            Map<String, Object> effectiveAttrs = getEffectiveAttrs(run);
            Map<String, Object> carried = intersectAttrs(effectiveAttrs, attrs);

            if (carried.isEmpty()) continue;

            indices.add(i);
            start = Math.min(start, run.getLogicalStart());
            end = Math.max(end, run.getLogicalStart() + run.length());
        }

        if (indices.isEmpty()) {
            log.debug("[HELPER:COLLECT] collectInsertGroupRunsWithAttrs groupId={} — no matching runs", groupId);
            return null;
        }

        log.debug("[HELPER:COLLECT] collectInsertGroupRunsWithAttrs groupId={} indices={} start={} end={}",
                groupId, indices, start, end);
        return new InsertGroupCollection(indices, start, end);
    }

    public static void moveAttrsFromBaseToSuggestionForRuns(
            List<ReviewRun> runs,
            List<Integer> indices,
            Map<String, Object> attrs
    ) {
        if (runs == null || indices == null || attrs == null || attrs.isEmpty()) return;

        for (Integer idx : indices) {
            if (idx == null || idx < 0 || idx >= runs.size()) {
                log.warn("[HELPER:MOVE] moveAttrsFromBaseToSuggestion: idx={} out of range (runs.size={})", idx, runs.size());
                continue;
            }

            ReviewRun run = runs.get(idx);
            Map<String, Object> base = new LinkedHashMap<>(run.getBaseAttributes() != null ? run.getBaseAttributes() : Collections.emptyMap());
            Map<String, Object> suggestion = new LinkedHashMap<>(run.getSuggestionAttributes() != null ? run.getSuggestionAttributes() : Collections.emptyMap());

            for (Map.Entry<String, Object> entry : attrs.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (Objects.equals(base.get(key), value)) {
                    base.remove(key);
                    log.debug("[HELPER:MOVE] run idx={} moved key={} from base to suggestion", idx, key);
                }
                suggestion.put(key, value);
            }

            run.setBaseAttributes(base);
            run.setSuggestionAttributes(suggestion);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Format suggestion find/create
    // ─────────────────────────────────────────────────────────────────────────

    public static FormatSuggestionItem findOrCreateFormatSuggestionByIdentity(
            List<FormatSuggestionItem> formatSuggestions,
            String actorEmail,
            String createdAt,
            String attrKey,
            Object attrValue
    ) {
        FormatSuggestionItem existing = formatSuggestions.stream()
                .filter(f -> actorEmail.equals(f.getActorEmail()))
                .filter(f -> attrKey.equals(f.getAttributeKey()))
                .filter(f -> Objects.equals(attrValue, f.getAttributeValue()))
                .findFirst().orElse(null);

        if (existing != null) {
            log.debug("[HELPER:FMT] findOrCreateByIdentity FOUND groupId={} actor={} key={} value={}",
                    existing.getGroupId(), actorEmail, attrKey, attrValue);

            if (createdAt != null && existing.getCreatedAt() != null
                    && createdAt.compareTo(existing.getCreatedAt()) > 0) {
                existing.setCreatedAt(createdAt);
            }
            return existing;
        }

        FormatSuggestionItem created = FormatSuggestionItem.builder()
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

        formatSuggestions.add(created);
        log.debug("[HELPER:FMT] findOrCreateByIdentity CREATED groupId={} actor={} key={} value={}",
                created.getGroupId(), actorEmail, attrKey, attrValue);
        return created;
    }

    public static void addInsertDependency(FormatSuggestionItem item, String insertGroupId) {
        if (item == null || insertGroupId == null) return;
        if (!item.getDependsOnInsertGroupIds().contains(insertGroupId)) {
            item.getDependsOnInsertGroupIds().add(insertGroupId);
            log.debug("[HELPER:FMT] addInsertDependency groupId={} -> insertGroupId={}", item.getGroupId(), insertGroupId);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reference helpers
    // ─────────────────────────────────────────────────────────────────────────

    public static List<Reference> cloneSuggestionReferences(List<Reference> references) {
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
    public static SuggestionReferenceSplit splitSuggestionReferences(
            List<Reference> references,
            int splitAbsPos
    ) {
        List<Reference> left = new ArrayList<>();
        List<Reference> right = new ArrayList<>();

        if (references == null || references.isEmpty()) {
            return new SuggestionReferenceSplit(left, right);
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

                log.debug("[HELPER:REFS] splitSuggestionReferences at absPos={} splitting ref [{}+{}] into left[{}] right[{}]",
                        splitAbsPos, start, reference.getLength(), leftLen, rightLen);

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

        log.debug("[HELPER:REFS] splitSuggestionReferences splitAbsPos={} left={} right={}", splitAbsPos, left, right);
        return new SuggestionReferenceSplit(left, right);
    }

    public static List<Reference> appendSuggestionReferences(
            List<Reference> base,
            List<Reference> incoming
    ) {
        List<Reference> out = new ArrayList<>();
        if (base != null) for (Reference s : base) out = appendAndCoalesceSuggestionReference(out, s);
        if (incoming != null) for (Reference s : incoming) out = appendAndCoalesceSuggestionReference(out, s);
        return out;
    }

    public static InsertSuggestion copyInsertSuggestion(InsertSuggestion src) {
        if (src == null) return null;
        return InsertSuggestion.builder()
                .groupId(src.getGroupId())
                .actorEmail(src.getActorEmail())
                .createdAt(src.getCreatedAt())
                .build();
    }

    public static DeleteSuggestion copyDeleteSuggestion(DeleteSuggestion src) {
        if (src == null) return null;
        return DeleteSuggestion.builder()
                .groupId(src.getGroupId())
                .actorEmail(src.getActorEmail())
                .createdAt(src.getCreatedAt())
                .type(src.getType() != null
                        ? src.getType()
                        : DeleteSuggestion.DeleteSuggestionType.TEXT)
                .build();
    }

    /**
     * Add a provenance reference for a suggestion.
     * reviewStart    = where this slice appears in the runtime review document.
     * componentStart = where this slice starts inside the original op component text.
     */
    public static List<Reference> addSuggestionReference(
            List<Reference> references,
            int reviewStart,
            int componentStart,
            int length,
            String opId,
            int componentIndex
    ) {
        List<Reference> out = cloneSuggestionReferences(references);

        if (length <= 0) {
            log.warn("[HELPER:REFS] addSuggestionReference: length={} <= 0 — skipping (opId={} compIdx={} reviewStart={} componentStart={})",
                    length, opId, componentIndex, reviewStart, componentStart);
            return out;
        }

        if (reviewStart < 0) {
            log.error("[HELPER:REFS:ERR] addSuggestionReference: reviewStart={} < 0 — opId={} compIdx={} componentStart={} length={}",
                    reviewStart, opId, componentIndex, componentStart, length);
        }

        if (componentStart < 0) {
            log.error("[HELPER:REFS:ERR] addSuggestionReference: componentStart={} < 0 — opId={} compIdx={} reviewStart={} length={}",
                    componentStart, opId, componentIndex, reviewStart, length);
        }

        Reference incoming = Reference.builder()
                .reviewStart(reviewStart)
                .componentStart(componentStart)
                .length(length)
                .opId(opId)
                .componentIndex(componentIndex)
                .build();

        List<Reference> result = appendAndCoalesceSuggestionReference(out, incoming);

        log.debug("[HELPER:REFS] addSuggestionReference opId={} compIdx={} reviewStart={} componentStart={} length={} => refs={}",
                opId, componentIndex, reviewStart, componentStart, length, result);

        return result;
    }

    public static List<Reference> appendAndCoalesceSuggestionReference(
            List<Reference> references,
            Reference incoming
    ) {
        List<Reference> out = cloneSuggestionReferences(references);

        if (incoming == null || incoming.getLength() <= 0) return out;

        if (!out.isEmpty()) {
            Reference last = out.get(out.size() - 1);
            if (canCoalesceSuggestionReferences(last, incoming)) {
                log.debug("[HELPER:REFS] coalescing refs: last=[{}+{}] + incoming=[{}+{}]",
                        last.getReviewStart(), last.getLength(),
                        incoming.getReviewStart(), incoming.getLength());
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
    public static boolean canCoalesceSuggestionReferences(Reference left, Reference right) {
        if (left == null || right == null) return false;

        boolean sameRef = Objects.equals(left.getOpId(), right.getOpId())
                && Objects.equals(left.getComponentIndex(), right.getComponentIndex());
        boolean adjacentReview = left.getReviewStart() + left.getLength() == right.getReviewStart();
        boolean adjacentComponent = left.getComponentStart() + left.getLength() == right.getComponentStart();

        return sameRef && adjacentReview && adjacentComponent;
    }

    /**
     * When new text is inserted, existing suggestion references after the insert
     * point must move forward.
     * References belonging to the newly inserted group are skipped.
     */
    public void shiftSuggestionReferenceReviewStarts(
            List<ReviewRun> runs,
            int insertPos,
            int shiftLen,
            String insertedGroupId
    ) {
        if (shiftLen <= 0) {
            log.warn("[HELPER:SHIFT] shiftSuggestionReferenceReviewStarts: shiftLen={} — no-op", shiftLen);
            return;
        }

        int shiftedCount = 0;

        for (ReviewRun run : runs) {
            boolean belongsToInsertedGroup = run.getInsertSuggestion() != null
                    && Objects.equals(insertedGroupId, run.getInsertSuggestion().getGroupId());

            boolean hasSuggestionRefs = run.getInsertSuggestion() != null || run.getDeleteSuggestion() != null;

            if (!hasSuggestionRefs || belongsToInsertedGroup) continue;

            for (Reference reference : run.getReferences()) {
                if (reference.getReviewStart() >= insertPos) {
                    log.debug("[HELPER:SHIFT] shifting ref reviewStart {} -> {} for run text='{}'",
                            reference.getReviewStart(), reference.getReviewStart() + shiftLen,
                            runTextForLog(run));
                    reference.setReviewStart(reference.getReviewStart() + shiftLen);
                    shiftedCount++;
                }
            }
        }

        log.debug("[HELPER:SHIFT] shiftSuggestionReferenceReviewStarts insertPos={} shiftLen={} shiftedCount={}",
                insertPos, shiftLen, shiftedCount);
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

        List<ReviewRange> merged = mergeOverlappingRanges(raw);
        log.debug("[HELPER:range] deriveMergedRangesFromReferences refs={} => ranges={}", references, merged);
        return merged;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Format suggestion range checks
    // ─────────────────────────────────────────────────────────────────────────

    public static boolean formatSuggestionCoversRange(
            FormatSuggestionItem item,
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

    public static boolean formatSuggestionOverlapsRange(
            FormatSuggestionItem item,
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

    public static boolean formatSuggestionTouchesOrOverlapsRange(
            FormatSuggestionItem item,
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
    // Format suggestion find/create (compatibility)
    // ─────────────────────────────────────────────────────────────────────────

    public static FormatSuggestionItem findOrCreateCompatibleFormatSuggestion(
            List<FormatSuggestionItem> formatSuggestions,
            String actorEmail,
            String createdAt,
            String attrKey,
            Object attrValue,
            int rangeStart,
            int rangeEnd
    ) {
        List<FormatSuggestionItem> matches = formatSuggestions.stream()
                .filter(f -> actorEmail.equals(f.getActorEmail()))
                .filter(f -> attrKey.equals(f.getAttributeKey()))
                .filter(f -> Objects.equals(attrValue, f.getAttributeValue()))
                .filter(f -> formatSuggestionTouchesOrOverlapsRange(f, rangeStart, rangeEnd))
                .toList();

        if (matches.isEmpty()) {
            FormatSuggestionItem created = FormatSuggestionItem.builder()
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

            formatSuggestions.add(created);
            log.debug("[HELPER:FMT] findOrCreateCompatible CREATED groupId={} actor={} key={} value={} range=[{},{}]",
                    created.getGroupId(), actorEmail, attrKey, attrValue, rangeStart, rangeEnd);
            return created;
        }

        FormatSuggestionItem primary = matches.get(0);
        log.debug("[HELPER:FMT] findOrCreateCompatible FOUND primary groupId={} matches={}",
                primary.getGroupId(), matches.size());

        for (int i = 1; i < matches.size(); i++) {
            FormatSuggestionItem other = matches.get(i);
            log.debug("[HELPER:FMT] findOrCreateCompatible merging groupId={} into primary groupId={}",
                    other.getGroupId(), primary.getGroupId());

            primary.setReferences(appendSuggestionReferences(primary.getReferences(), other.getReferences()));

            for (String dep : other.getDependsOnInsertGroupIds()) {
                if (!primary.getDependsOnInsertGroupIds().contains(dep)) primary.getDependsOnInsertGroupIds().add(dep);
            }

            for (String dep : other.getDependsOnDeleteGroupIds()) {
                if (!primary.getDependsOnDeleteGroupIds().contains(dep)) primary.getDependsOnDeleteGroupIds().add(dep);
            }

            if (other.getCreatedAt().compareTo(primary.getCreatedAt()) > 0) primary.setCreatedAt(other.getCreatedAt());

            formatSuggestions.removeIf(f -> f.getGroupId().equals(other.getGroupId()));
        }
        return primary;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Format suggestion inherit insert
    // ─────────────────────────────────────────────────────────────────────────

    public static boolean formatSuggestionShouldInheritInsert(
            FormatSuggestionItem group,
            int insertPos
    ) {
        if (group == null) return false;

        for (ReviewRange range : deriveMergedRangesFromReferences(group.getReferences())) {
            int rangeStart = range.getStart();
            int rangeEnd = rangeStart + range.getLength();
            if (insertPos >= rangeStart && insertPos <= rangeEnd) {
                log.debug("[HELPER:FMT] formatSuggestionShouldInheritInsert groupId={} insertPos={} => true (range [{},{}])",
                        group.getGroupId(), insertPos, rangeStart, rangeEnd);
                return true;
            }
        }

        return false;
    }

    public static void extendFormatGroupForInheritedInsert(
            FormatSuggestionItem group,
            int insertPos,
            int insertLength,
            String opId,
            int compIdx,
            String currentInsertGroupId
    ) {
        if (group == null || insertLength <= 0) return;

        log.info("[HELPER:FMT] extendFormatGroupForInheritedInsert groupId={} insertPos={} insertLength={} opId={} compIdx={} insertGroupId={}",
                group.getGroupId(), insertPos, insertLength, opId, compIdx, currentInsertGroupId);

        List<Reference> before = cloneSuggestionReferences(group.getReferences());

        group.setReferences(shiftSuggestionReferencesForInsert(group.getReferences(), insertPos, insertLength));

        log.debug("[HELPER:FMT] extendFormat: refs before shift={} after shift={}",
                before, group.getReferences());

        group.setReferences(addSuggestionReference(
                group.getReferences(), insertPos, 0, insertLength, opId, compIdx));

        if (!group.getDependsOnInsertGroupIds().contains(currentInsertGroupId)) {
            group.getDependsOnInsertGroupIds().add(currentInsertGroupId);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reference range manipulation
    // ─────────────────────────────────────────────────────────────────────────

    public static List<Reference> removeRangeFromSuggestionReferencesWithoutShift(
            List<Reference> references,
            int removeStart,
            int removeLength
    ) {
        List<Reference> out = new ArrayList<>();

        if (references == null || references.isEmpty() || removeLength <= 0) {
            return cloneSuggestionReferences(references);
        }

        int removeEnd = removeStart + removeLength;

        for (Reference reference : references) {
            if (reference == null) continue;

            int referenceStart = reference.getReviewStart();
            int referenceEnd = referenceStart + reference.getLength();

            if (referenceEnd <= removeStart || referenceStart >= removeEnd) {
                out = appendAndCoalesceSuggestionReference(out, reference);
                continue;
            }

            int leftLen = Math.max(0, removeStart - referenceStart);
            int rightLen = Math.max(0, referenceEnd - removeEnd);

            log.debug("[HELPER:REFS] removeRangeWithoutShift ref=[{}+{}] removeRange=[{},{}] leftLen={} rightLen={}",
                    referenceStart, reference.getLength(), removeStart, removeEnd, leftLen, rightLen);

            if (leftLen > 0) {
                out = appendAndCoalesceSuggestionReference(out,
                        Reference.builder()
                                .reviewStart(referenceStart)
                                .componentStart(reference.getComponentStart())
                                .length(leftLen)
                                .opId(reference.getOpId())
                                .componentIndex(reference.getComponentIndex())
                                .build());
            }

            if (rightLen > 0) {
                out = appendAndCoalesceSuggestionReference(out,
                        Reference.builder()
                                .reviewStart(removeEnd)
                                .componentStart(reference.getComponentStart() + Math.max(0, removeEnd - referenceStart))
                                .length(rightLen)
                                .opId(reference.getOpId())
                                .componentIndex(reference.getComponentIndex())
                                .build());
            }
        }

        log.debug("[HELPER:REFS] removeRangeWithoutShift removeStart={} removeLength={} in={} out={}",
                removeStart, removeLength, references, out);
        return out;
    }

    public static List<Reference> deleteRangeFromSuggestionReferencesAndShift(
            List<Reference> references,
            int deleteStart,
            int deleteLength
    ) {
        List<Reference> out = new ArrayList<>();

        if (references == null || references.isEmpty() || deleteLength <= 0) {
            return cloneSuggestionReferences(references);
        }

        int deleteEnd = deleteStart + deleteLength;

        for (Reference reference : references) {
            if (reference == null) continue;

            int referenceStart = reference.getReviewStart();
            int referenceEnd = referenceStart + reference.getLength();

            if (referenceEnd <= deleteStart) {
                out = appendAndCoalesceSuggestionReference(out, reference);
                continue;
            }

            if (referenceStart >= deleteEnd) {
                out = appendAndCoalesceSuggestionReference(out,
                        Reference.builder()
                                .reviewStart(referenceStart - deleteLength)
                                .componentStart(reference.getComponentStart())
                                .length(reference.getLength())
                                .opId(reference.getOpId())
                                .componentIndex(reference.getComponentIndex())
                                .build());
                log.debug("[HELPER:REFS] deleteRangeAndShift shifting ref [{}+{}] -> [{}+{}]",
                        referenceStart, reference.getLength(), referenceStart - deleteLength, reference.getLength());
                continue;
            }

            int leftLen = Math.max(0, deleteStart - referenceStart);
            int rightLen = Math.max(0, referenceEnd - deleteEnd);

            log.debug("[HELPER:REFS] deleteRangeAndShift ref=[{}+{}] deleteRange=[{},{}] leftLen={} rightLen={}",
                    referenceStart, reference.getLength(), deleteStart, deleteEnd, leftLen, rightLen);

            if (leftLen > 0) {
                out = appendAndCoalesceSuggestionReference(out,
                        Reference.builder()
                                .reviewStart(referenceStart)
                                .componentStart(reference.getComponentStart())
                                .length(leftLen)
                                .opId(reference.getOpId())
                                .componentIndex(reference.getComponentIndex())
                                .build());
            }

            if (rightLen > 0) {
                out = appendAndCoalesceSuggestionReference(out,
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

    public void shiftFormatSuggestionReferences(
            List<FormatSuggestionItem> formatSuggestions,
            int insertPos,
            int shiftLen,
            Set<String> excludedGroupIds
    ) {
        if (formatSuggestions == null || shiftLen <= 0) {
            if (shiftLen <= 0) log.warn("[HELPER:SHIFT] shiftFormatSuggestionReferences: shiftLen={} — no-op", shiftLen);
            return;
        }

        Set<String> excluded = excludedGroupIds != null ? excludedGroupIds : Collections.emptySet();

        for (FormatSuggestionItem fmt : formatSuggestions) {
            if (fmt == null || excluded.contains(fmt.getGroupId())) {
                log.debug("[HELPER:SHIFT] skipping groupId={} (excluded)", fmt != null ? fmt.getGroupId() : "null");
                continue;
            }

            List<Reference> before = cloneSuggestionReferences(fmt.getReferences());

            fmt.setReferences(shiftSuggestionReferencesForInsert(fmt.getReferences(), insertPos, shiftLen));

            log.debug("[HELPER:SHIFT] shiftFormatSuggestionReferences groupId={} insertPos={} shiftLen={} before={} after={}",
                    fmt.getGroupId(), insertPos, shiftLen, before, fmt.getReferences());
        }
    }

    public static List<Reference> collectReferencesForRunIndices(
            List<ReviewRun> runs,
            List<Integer> indices
    ) {
        List<Reference> refs = new ArrayList<>();
        if (runs == null || indices == null) return refs;

        for (Integer idx : indices) {
            if (idx == null || idx < 0 || idx >= runs.size()) {
                log.warn("[HELPER:REFS] collectReferencesForRunIndices: idx={} out of range (runs.size={})", idx, runs.size());
                continue;
            }
            ReviewRun run = runs.get(idx);
            refs = appendSuggestionReferences(refs, run.getReferences());
        }

        log.debug("[HELPER:REFS] collectReferencesForRunIndices indices={} => refs={}", indices, refs);
        return refs;
    }

    public static void deleteRangeFromRunReferencesAndShift(
            List<ReviewRun> runs,
            int deleteStart,
            int deleteLength
    ) {
        if (runs == null || runs.isEmpty() || deleteLength <= 0) return;

        int count = 0;
        for (ReviewRun run : runs) {
            if (run.getReferences() == null || run.getReferences().isEmpty()) continue;
            List<Reference> before = cloneSuggestionReferences(run.getReferences());
            run.setReferences(deleteRangeFromSuggestionReferencesAndShift(run.getReferences(), deleteStart, deleteLength));
            if (!run.getReferences().equals(before)) {
                log.debug("[HELPER:REFS] deleteRangeFromRunReferences run text='{}' before={} after={}",
                        runTextForLog(run));
                count++;
            }
        }
        log.debug("[HELPER:REFS] deleteRangeFromRunReferencesAndShift deleteStart={} deleteLength={} modifiedRuns={}",
                deleteStart, deleteLength, count);
    }

    public static void deleteRangeFromFormatSuggestionReferencesAndShift(
            Collection<FormatSuggestionItem> formatSuggestions,
            int deleteStart,
            int deleteLength
    ) {
        if (formatSuggestions == null || formatSuggestions.isEmpty() || deleteLength <= 0) return;

        for (FormatSuggestionItem fmt : formatSuggestions) {
            if (fmt.getReferences() == null || fmt.getReferences().isEmpty()) continue;

            List<Reference> before = cloneSuggestionReferences(fmt.getReferences());
            fmt.setReferences(deleteRangeFromSuggestionReferencesAndShift(fmt.getReferences(), deleteStart, deleteLength));

            log.debug("[HELPER:REFS] deleteRangeFromFormatSuggestionReferences groupId={} deleteStart={} deleteLength={} before={} after={}",
                    fmt.getGroupId(), deleteStart, deleteLength, before, fmt.getReferences());
        }
    }

    public static List<Reference> shiftSuggestionReferencesForInsert(
            List<Reference> references,
            int insertPos,
            int insertLength
    ) {
        List<Reference> out = new ArrayList<>();

        if (references == null || references.isEmpty() || insertLength <= 0) {
            return cloneSuggestionReferences(references);
        }

        for (Reference reference : references) {
            if (reference == null) continue;

            int referenceStart = reference.getReviewStart();
            int referenceEnd = referenceStart + reference.getLength();

            if (referenceEnd <= insertPos) {
                out = appendAndCoalesceSuggestionReference(out, reference);
                continue;
            }

            if (referenceStart >= insertPos) {
                out = appendAndCoalesceSuggestionReference(out,
                        Reference.builder()
                                .reviewStart(referenceStart + insertLength)
                                .componentStart(reference.getComponentStart())
                                .length(reference.getLength())
                                .opId(reference.getOpId())
                                .componentIndex(reference.getComponentIndex())
                                .build());
                log.debug("[HELPER:REFS] shiftForInsert ref [{}+{}] shifted -> [{}+{}]",
                        referenceStart, reference.getLength(), referenceStart + insertLength, reference.getLength());
                continue;
            }

            int leftLen = insertPos - referenceStart;
            int rightLen = referenceEnd - insertPos;

            log.debug("[HELPER:REFS] shiftForInsert ref [{}+{}] straddles insertPos={} — splitting leftLen={} rightLen={}",
                    referenceStart, reference.getLength(), insertPos, leftLen, rightLen);

            if (leftLen > 0) {
                out = appendAndCoalesceSuggestionReference(out,
                        Reference.builder()
                                .reviewStart(referenceStart)
                                .componentStart(reference.getComponentStart())
                                .length(leftLen)
                                .opId(reference.getOpId())
                                .componentIndex(reference.getComponentIndex())
                                .build());
            }

            if (rightLen > 0) {
                out = appendAndCoalesceSuggestionReference(out,
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

    public static DeleteSuggestion.DeleteSuggestionType promotedDeleteType(
            DeleteSuggestion.DeleteSuggestionType current,
            boolean deletingNewline
    ) {
        DeleteSuggestion.DeleteSuggestionType safeCurrent =
                current != null ? current : DeleteSuggestion.DeleteSuggestionType.TEXT;

        if (!deletingNewline) {
            return safeCurrent;
        }

        if (safeCurrent == DeleteSuggestion.DeleteSuggestionType.TEXT) {
            return DeleteSuggestion.DeleteSuggestionType.SINGLE_LINE;
        }

        return DeleteSuggestion.DeleteSuggestionType.MULTI_LINE;
    }

    public static void applyDeleteTypeToGroupRuns(
            List<ReviewRun> runs,
            String groupId,
            DeleteSuggestion.DeleteSuggestionType type
    ) {
        for (ReviewRun run : runs) {
            if (run.getDeleteSuggestion() == null) continue;
            if (!groupId.equals(run.getDeleteSuggestion().getGroupId())) continue;

            run.getDeleteSuggestion().setType(type);
        }
    }

    public static Map<String, Object> cloneEmbed(Object embed) {
        if (embed instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return out;
        }

        return new LinkedHashMap<>();
    }

    public static String runTextForLog(ReviewRun run) {
        if (run == null) return "null";
        if (run.isEmbed()) return "[embed]";
        if (run.getText() == null) return "[empty-run]";
        return run.getText().replace("\n", "\\n");
    }

    public static List<InsertFragment> buildInsertFragments(Object insertValue) {
        List<InsertFragment> fragments = new ArrayList<>();

        if (insertValue instanceof String text) {
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

        if (insertValue instanceof Map<?, ?> embed) {
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

    public record InsertFragment(
            String text,
            Object embed,
            int length,
            int componentStart,
            boolean newline
    ) {
        public boolean isEmbed() {
            return embed != null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Records
    // ─────────────────────────────────────────────────────────────────────────

    public record InsertGroupCollection(List<Integer> indices, int start, int end) {}

    public record SuggestionReferenceSplit(List<Reference> left, List<Reference> right) {}
}