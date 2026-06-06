package com.example.notes.dto.attribution;

import com.example.notes.dto.ot.Delta;
import com.example.notes.dto.ot.Op;
import com.example.notes.dto.ot.OpState;
import com.example.notes.dto.ot.TextOperation;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class ReviewOperationAccumulator {
    private record CompKey(String opId, int componentIndex) {}
    private record CharRange(int start, int length) {}

    private final Map<CompKey, List<CharRange>> acceptedTextRanges = new LinkedHashMap<>();
    private final Map<CompKey, List<CharRange>> rejectedTextRanges = new LinkedHashMap<>();

    private final Map<CompKey, Map<String, List<CharRange>>> acceptedFormatRanges = new LinkedHashMap<>();
    private final Map<CompKey, Map<String, List<CharRange>>> rejectedFormatRanges = new LinkedHashMap<>();

    public void recordInsertCancellation(
            String opId,
            int componentIndex,
            int componentStart,
            int length
    ) {
        recordCancellation(opId, componentIndex, componentStart, length, null);
    }

    public void recordDeleteCancellation(
            String opId,
            int componentIndex,
            int componentStart,
            int length
    ) {
        recordCancellation(opId, componentIndex, componentStart, length, null);
    }

    public void recordFormatCancellation(
            String opId,
            int componentIndex,
            int componentStart,
            int length,
            String attributeKey
    ) {
        recordCancellation(opId, componentIndex, componentStart, length, attributeKey);
    }

    private void recordCancellation(
            String opId,
            int componentIndex,
            int componentStart,
            int length,
            String attributeKey
    ) {
        if (opId == null || componentIndex < 0 || componentStart < 0 || length <= 0) {
            return;
        }

        CompKey key = new CompKey(opId, componentIndex);
        CharRange range = new CharRange(componentStart, length);

        if (attributeKey != null && !attributeKey.isBlank()) {
            acceptedFormatRanges
                    .computeIfAbsent(key, k -> new LinkedHashMap<>())
                    .computeIfAbsent(attributeKey, k -> new ArrayList<>())
                    .add(range);
            return;
        }

        acceptedTextRanges
                .computeIfAbsent(key, k -> new ArrayList<>())
                .add(range);
    }

    public void recordAcceptedReference(ReviewDecisionReference ref) {
        record(ref, true);
    }

    public void recordRejectedReference(ReviewDecisionReference ref) {
        record(ref, false);
    }

    private void record(ReviewDecisionReference ref, boolean accepted) {
        if (ref == null || ref.getOpId() == null || ref.getComponentIndex() < 0 || ref.getComponentStart() < 0 || ref.getLength() <= 0) {
            return;
        }

        CompKey key = new CompKey(ref.getOpId(), ref.getComponentIndex());
        CharRange range = new CharRange(ref.getComponentStart(), ref.getLength());

        if (ref.getAttributeKey() != null && !ref.getAttributeKey().isBlank()) {
            Map<CompKey, Map<String, List<CharRange>>> target =
                    accepted ? acceptedFormatRanges : rejectedFormatRanges;

            target.computeIfAbsent(key, k -> new LinkedHashMap<>())
                    .computeIfAbsent(ref.getAttributeKey(), k -> new ArrayList<>())
                    .add(range);
            return;
        }

        // Insert/delete distinction is resolved later from the actual op type.
        Map<CompKey, List<CharRange>> target =
                accepted ? acceptedTextRanges : rejectedTextRanges;

        target.computeIfAbsent(key, k -> new ArrayList<>()).add(range);
    }

    public boolean isEmpty() {
        return acceptedTextRanges.isEmpty()
                && rejectedTextRanges.isEmpty()
                && acceptedFormatRanges.isEmpty()
                && rejectedFormatRanges.isEmpty();
    }

    private boolean isCancellationEmpty() {
        return acceptedTextRanges.isEmpty()
                && acceptedFormatRanges.isEmpty();
    }

    public boolean flushCancellationsAndReturnChanged(List<TextOperation> logOps) {
        if (logOps == null || logOps.isEmpty() || isCancellationEmpty()) {
            return false;
        }

        boolean changed = false;
        Map<String, OpReviewDecision> grouped = groupByOp(logOps);

        for (Map.Entry<String, OpReviewDecision> entry : grouped.entrySet()) {
            String opId = entry.getKey();
            OpReviewDecision decision = entry.getValue();

            TextOperation textOp = findOp(logOps, opId);

            if (textOp == null || textOp.getDelta() == null || textOp.getDelta().ops == null) {
                continue;
            }

            SplitResult split = splitOperation(textOp.getDelta(), decision, opId);

            Delta cancelled = split.accepted().chop();
            Delta pending = split.pending().chop();

            boolean hasCancelled = hasEffectiveOps(cancelled);
            boolean hasPending = hasEffectiveOps(pending);

            if (!hasCancelled && !hasPending) {
                continue;
            }

            int originalIndex = logOps.indexOf(textOp);
            logOps.remove(textOp);

            int insertAt = originalIndex;

            TextOperation deadOp = new TextOperation(
                    cancelled,
                    textOp.getActorEmail(),
                    textOp.getRevision(),
                    OpState.DEAD,
                    textOp.getCreatedAt()
            );

            logOps.add(insertAt++, deadOp);

            if (hasPending) {
                textOp.setDelta(pending);
                textOp.setState(OpState.PENDING);
                logOps.add(insertAt, textOp);
            }

            changed = true;
        }

        return changed;
    }

    public ReviewApplyResult applyReviewDecisionsToRevisionLog(List<TextOperation> logOps) {
        if (logOps == null || logOps.isEmpty() || isEmpty()) {
            return new ReviewApplyResult(false, new Delta());
        }

        boolean changed = false;
        Delta committedMasterDelta = new Delta();

        Map<String, OpReviewDecision> grouped = groupByOp(logOps);

        for (Map.Entry<String, OpReviewDecision> entry : grouped.entrySet()) {
            String opId = entry.getKey();
            OpReviewDecision decision = entry.getValue();

            TextOperation textOp = findOp(logOps, opId);
            if (textOp == null || textOp.getDelta() == null || textOp.getDelta().ops == null) {
                continue;
            }

            SplitResult split = splitOperation(textOp.getDelta(), decision, opId);

            Delta accepted = split.accepted().chop();
            Delta rejected = split.rejected().chop();
            Delta pending = split.pending().chop();

            boolean hasAccepted = hasEffectiveOps(accepted);
            boolean hasRejected = hasEffectiveOps(rejected);
            boolean hasPending = hasEffectiveOps(pending);

            if (!hasAccepted && !hasRejected && !hasPending) {
                continue;
            }

            int originalIndex = logOps.indexOf(textOp);
            logOps.remove(textOp);

            int insertAt = originalIndex;

            if (hasAccepted) {
                TextOperation acceptedOp = new TextOperation(
                        accepted,
                        textOp.getActorEmail(),
                        textOp.getRevision(),
                        OpState.COMMITTED,
                        textOp.getCreatedAt()
                );

                logOps.add(insertAt++, acceptedOp);
                committedMasterDelta = committedMasterDelta.compose(accepted);
            }

            if (hasRejected) {
                TextOperation rejectedOp = new TextOperation(
                        rejected,
                        textOp.getActorEmail(),
                        textOp.getRevision(),
                        OpState.REJECTED,
                        textOp.getCreatedAt()
                );

                logOps.add(insertAt++, rejectedOp);
            }

            if (hasPending) {
                textOp.setDelta(pending);
                textOp.setState(OpState.PENDING);
                logOps.add(insertAt, textOp);
            }

            changed = true;
        }

        return new ReviewApplyResult(changed, committedMasterDelta.chop());
    }

    private SplitResult splitOperation(
            Delta original,
            OpReviewDecision decision,
            String opId
    ) {
        Delta accepted = new Delta();
        Delta rejected = new Delta();
        Delta pending = new Delta();

        for (int i = 0; i < original.ops.size(); i++) {
            Op op = original.ops.get(i);

            if (op.isRetain() && op.getAttributes() == null) {
                int len = (Integer) op.getRetain();
                accepted.retain(len, null);
                rejected.retain(len, null);
                pending.retain(len, null);
                continue;
            }

            else if (op.isInsert() && op.getInsert() instanceof String text) {
                List<CharRange> acceptedRanges =
                        clampRanges(decision.acceptedInsertRanges.get(i), text.length());

                List<CharRange> rejectedRanges =
                        clampRanges(decision.rejectedInsertRanges.get(i), text.length());

                Map<String, List<CharRange>> acceptedFormats =
                        decision.acceptedFormatRanges.getOrDefault(i, Collections.emptyMap());

                Map<String, List<CharRange>> rejectedFormats =
                        decision.rejectedFormatRanges.getOrDefault(i, Collections.emptyMap());

                splitInsert(
                        text,
                        op.getAttributes(),
                        acceptedRanges,
                        rejectedRanges,
                        acceptedFormats,
                        rejectedFormats,
                        accepted,
                        rejected,
                        pending
                );

                continue;
            }

            else if (op.isInsert() && op.getInsert() != null && !(op.getInsert() instanceof String)) {
                List<CharRange> acceptedRanges =
                        clampRanges(decision.acceptedInsertRanges.get(i), 1);

                List<CharRange> rejectedRanges =
                        clampRanges(decision.rejectedInsertRanges.get(i), 1);

                splitEmbedInsert(
                        op.getInsert(),
                        op.getAttributes(),
                        acceptedRanges,
                        rejectedRanges,
                        accepted,
                        rejected,
                        pending
                );

                continue;
            }

            else if (op.isDelete()) {
                int len = op.getDelete();

                List<CharRange> acceptedRanges = clampRanges(decision.acceptedDeleteRanges.get(i), len);
                List<CharRange> rejectedRanges = clampRanges(decision.rejectedDeleteRanges.get(i), len);

                splitDelete(len, acceptedRanges, rejectedRanges, accepted, rejected, pending);
                continue;
            }

            else if (op.isRetain() && op.getAttributes() != null) {
                int len = (Integer) op.getRetain();

                Map<String, List<CharRange>> acceptedFormats =
                        decision.acceptedFormatRanges.getOrDefault(i, Collections.emptyMap());

                Map<String, List<CharRange>> rejectedFormats =
                        decision.rejectedFormatRanges.getOrDefault(i, Collections.emptyMap());

                splitFormatRetain(len, op.getAttributes(), acceptedFormats, rejectedFormats, accepted, rejected, pending);
                continue;
            }

            log.warn("[REVIEW-ACC:WARN] opId={} unknown component idx={} op={}", opId, i, op);
        }

        return new SplitResult(accepted.chop(), rejected.chop(), pending.chop());
    }

    private void splitEmbedInsert(
            Object embed,
            Map<String, Object> attrs,
            List<CharRange> acceptedRanges,
            List<CharRange> rejectedRanges,
            Delta accepted,
            Delta rejected,
            Delta pending
    ) {
        List<SliceDecision> decisions =
                buildSliceDecisions(1, acceptedRanges, rejectedRanges);

        if (decisions.isEmpty()) {
            accepted.retain(1, null);
            rejected.retain(1, null);
            pending.insert(embed, attrs);
            return;
        }

        SliceDecision decision = decisions.get(0);

        if (decision.type() == DecisionType.ACCEPTED) {
            accepted.insert(embed, attrs);
            rejected.retain(1, null);
            pending.retain(1, null);
        } else {
            accepted.retain(1, null);
            rejected.insert(embed, attrs);
        }
    }

    private void splitInsert(
            String text,
            Map<String, Object> attrs,
            List<CharRange> acceptedRanges,
            List<CharRange> rejectedRanges,
            Map<String, List<CharRange>> acceptedFormats,
            Map<String, List<CharRange>> rejectedFormats,
            Delta accepted,
            Delta rejected,
            Delta pending
    ) {
        Map<String, Object> safeAttrs =
                attrs != null ? new LinkedHashMap<>(attrs) : new LinkedHashMap<>();

        Map<Integer, Set<String>> acceptedFormatAt =
                buildFormatPositionMap(text.length(), acceptedFormats);

        Map<Integer, Set<String>> rejectedFormatAt =
                buildFormatPositionMap(text.length(), rejectedFormats);

        int cursor = 0;
        List<SliceDecision> decisions =
                buildSliceDecisions(text.length(), acceptedRanges, rejectedRanges);

        for (SliceDecision decision : decisions) {
            if (decision.start() > cursor) {
                appendPendingInsertWithFormatDecisions(
                        text.substring(cursor, decision.start()),
                        cursor,
                        safeAttrs,
                        acceptedFormatAt,
                        rejectedFormatAt,
                        accepted,
                        rejected,
                        pending
                );
            }

            String part = text.substring(decision.start(), decision.end());

            if (decision.type() == DecisionType.ACCEPTED) {
                appendAcceptedInsertWithFormatDecisions(
                        part,
                        decision.start(),
                        safeAttrs,
                        acceptedFormatAt,
                        rejectedFormatAt,
                        accepted,
                        rejected,
                        pending
                );
            } else {
                accepted.retain(part.length(), null);
                rejected.insert(part, safeAttrs.isEmpty() ? null : safeAttrs);
                // Rejected insert should disappear from pending/base.
            }

            cursor = decision.end();
        }

        if (cursor < text.length()) {
            appendPendingInsertWithFormatDecisions(
                    text.substring(cursor),
                    cursor,
                    safeAttrs,
                    acceptedFormatAt,
                    rejectedFormatAt,
                    accepted,
                    rejected,
                    pending
            );
        }
    }

    private void appendAcceptedInsertWithFormatDecisions(
            String text,
            int absoluteStart,
            Map<String, Object> attrs,
            Map<Integer, Set<String>> acceptedFormatAt,
            Map<Integer, Set<String>> rejectedFormatAt,
            Delta accepted,
            Delta rejected,
            Delta pending
    ) {
        appendInsertByFormatDecision(
                text,
                absoluteStart,
                attrs,
                acceptedFormatAt,
                rejectedFormatAt,
                true,
                accepted,
                rejected,
                pending
        );
    }

    private void appendPendingInsertWithFormatDecisions(
            String text,
            int absoluteStart,
            Map<String, Object> attrs,
            Map<Integer, Set<String>> acceptedFormatAt,
            Map<Integer, Set<String>> rejectedFormatAt,
            Delta accepted,
            Delta rejected,
            Delta pending
    ) {
        appendInsertByFormatDecision(
                text,
                absoluteStart,
                attrs,
                acceptedFormatAt,
                rejectedFormatAt,
                false,
                accepted,
                rejected,
                pending
        );
    }

    private void appendInsertByFormatDecision(
            String text,
            int absoluteStart,
            Map<String, Object> attrs,
            Map<Integer, Set<String>> acceptedFormatAt,
            Map<Integer, Set<String>> rejectedFormatAt,
            boolean textAccepted,
            Delta accepted,
            Delta rejected,
            Delta pending
    ) {
        int local = 0;

        while (local < text.length()) {
            int absoluteIndex = absoluteStart + local;

            Set<String> acceptedKeys =
                    acceptedFormatAt.getOrDefault(absoluteIndex, Collections.emptySet());

            Set<String> rejectedKeys =
                    rejectedFormatAt.getOrDefault(absoluteIndex, Collections.emptySet());

            int end = local + 1;

            while (end < text.length()) {
                int nextAbsolute = absoluteStart + end;

                if (!Objects.equals(
                        acceptedFormatAt.getOrDefault(nextAbsolute, Collections.emptySet()),
                        acceptedKeys
                )) {
                    break;
                }

                if (!Objects.equals(
                        rejectedFormatAt.getOrDefault(nextAbsolute, Collections.emptySet()),
                        rejectedKeys
                )) {
                    break;
                }

                end++;
            }

            String part = text.substring(local, end);
            int len = part.length();

            Map<String, Object> acceptedAttrs = new LinkedHashMap<>(attrs);
            Map<String, Object> pendingAttrs = new LinkedHashMap<>(attrs);

            /*
             * Rejected format attrs must not appear in accepted/pending live content.
             */
            for (String key : rejectedKeys) {
                acceptedAttrs.remove(key);
                pendingAttrs.remove(key);
            }

            /*
             * If text is accepted but some attrs are still pending format suggestions,
             * do not commit those attrs with the inserted text.
             * Keep them as a pending retain over the newly accepted text.
             */
            if (textAccepted) {
                for (String key : attrs.keySet()) {
                    boolean explicitlyAccepted = acceptedKeys.contains(key);
                    boolean explicitlyRejected = rejectedKeys.contains(key);

                    if (!explicitlyAccepted && !explicitlyRejected) {
                        acceptedAttrs.remove(key);
                    }
                }

                accepted.insert(part, acceptedAttrs.isEmpty() ? null : acceptedAttrs);
                rejected.retain(len, null);

                Map<String, Object> pendingOnlyAttrs = new LinkedHashMap<>();

                for (String key : attrs.keySet()) {
                    boolean explicitlyAccepted = acceptedKeys.contains(key);
                    boolean explicitlyRejected = rejectedKeys.contains(key);

                    if (!explicitlyAccepted && !explicitlyRejected) {
                        pendingOnlyAttrs.put(key, attrs.get(key));
                    }
                }

                if (pendingOnlyAttrs.isEmpty()) {
                    pending.retain(len, null);
                } else {
                    pending.retain(len, pendingOnlyAttrs);
                }
            } else {
                /*
                 * Text is still pending, so pending keeps the insert.
                 * But rejected format attrs are removed from that pending insert.
                 */
                accepted.retain(len, null);
                rejected.retain(len, null);
                pending.insert(part, pendingAttrs.isEmpty() ? null : pendingAttrs);
            }

            local = end;
        }
    }

    private void splitDelete(
            int len,
            List<CharRange> acceptedRanges,
            List<CharRange> rejectedRanges,
            Delta accepted,
            Delta rejected,
            Delta pending
    ) {
        int cursor = 0;
        List<SliceDecision> decisions = buildSliceDecisions(len, acceptedRanges, rejectedRanges);

        for (SliceDecision decision : decisions) {
            if (decision.start() > cursor) {
                int pendingLen = decision.start() - cursor;
                accepted.retain(pendingLen, null);
                rejected.retain(pendingLen, null);
                pending.delete(pendingLen);
            }

            int partLen = decision.end() - decision.start();

            if (decision.type() == DecisionType.ACCEPTED) {
                accepted.delete(partLen);
                rejected.retain(partLen, null);
            } else {
                accepted.retain(partLen, null);
                rejected.delete(partLen);
                // Rejected delete should disappear from pending/base.
            }

            cursor = decision.end();
        }

        if (cursor < len) {
            int tailLen = len - cursor;
            accepted.retain(tailLen, null);
            rejected.retain(tailLen, null);
            pending.delete(tailLen);
        }
    }

    private void splitFormatRetain(
            int len,
            Map<String, Object> attrs,
            Map<String, List<CharRange>> acceptedFormats,
            Map<String, List<CharRange>> rejectedFormats,
            Delta accepted,
            Delta rejected,
            Delta pending
    ) {
        Map<Integer, Set<String>> acceptedAt = buildFormatPositionMap(len, acceptedFormats);
        Map<Integer, Set<String>> rejectedAt = buildFormatPositionMap(len, rejectedFormats);

        int cursor = 0;

        while (cursor < len) {
            int start = cursor;
            Set<String> acceptedKeys = acceptedAt.getOrDefault(cursor, Collections.emptySet());
            Set<String> rejectedKeys = rejectedAt.getOrDefault(cursor, Collections.emptySet());

            while (
                    cursor < len
                            && Objects.equals(acceptedAt.getOrDefault(cursor, Collections.emptySet()), acceptedKeys)
                            && Objects.equals(rejectedAt.getOrDefault(cursor, Collections.emptySet()), rejectedKeys)
            ) {
                cursor++;
            }

            int segLen = cursor - start;

            Map<String, Object> acceptedAttrs = pickAttrs(attrs, acceptedKeys);
            Map<String, Object> rejectedAttrs = pickAttrs(attrs, rejectedKeys);
            Map<String, Object> pendingAttrs = new LinkedHashMap<>(attrs);

            for (String key : acceptedKeys) pendingAttrs.remove(key);
            for (String key : rejectedKeys) pendingAttrs.remove(key);

            accepted.retain(segLen, acceptedAttrs.isEmpty() ? null : acceptedAttrs);
            rejected.retain(segLen, rejectedAttrs.isEmpty() ? null : rejectedAttrs);
            pending.retain(segLen, pendingAttrs.isEmpty() ? null : pendingAttrs);
        }
    }

    private Map<Integer, Set<String>> buildFormatPositionMap(
            int len,
            Map<String, List<CharRange>> rangesByAttr
    ) {
        Map<Integer, Set<String>> out = new HashMap<>();

        for (Map.Entry<String, List<CharRange>> entry : rangesByAttr.entrySet()) {
            String attrKey = entry.getKey();

            for (CharRange range : clampRanges(entry.getValue(), len)) {
                for (int i = range.start(); i < range.start() + range.length(); i++) {
                    out.computeIfAbsent(i, k -> new LinkedHashSet<>()).add(attrKey);
                }
            }
        }

        return out;
    }

    private Map<String, Object> pickAttrs(Map<String, Object> attrs, Set<String> keys) {
        Map<String, Object> out = new LinkedHashMap<>();

        for (String key : keys) {
            if (attrs.containsKey(key)) {
                out.put(key, attrs.get(key));
            }
        }

        return out;
    }

    private List<SliceDecision> buildSliceDecisions(
            int componentLength,
            List<CharRange> acceptedRanges,
            List<CharRange> rejectedRanges
    ) {
        List<SliceDecision> raw = new ArrayList<>();

        for (CharRange range : acceptedRanges) {
            raw.add(new SliceDecision(range.start(), range.start() + range.length(), DecisionType.ACCEPTED));
        }

        for (CharRange range : rejectedRanges) {
            raw.add(new SliceDecision(range.start(), range.start() + range.length(), DecisionType.REJECTED));
        }

        raw.sort(Comparator.comparingInt(SliceDecision::start));

        return getSliceDecisions(componentLength, raw);
    }

    private static List<SliceDecision> getSliceDecisions(int componentLength, List<SliceDecision> raw) {
        List<SliceDecision> out = new ArrayList<>();

        for (SliceDecision next : raw) {
            int start = Math.max(0, Math.min(next.start(), componentLength));
            int end = Math.max(start, Math.min(next.end(), componentLength));

            if (start >= end) continue;

            if (!out.isEmpty()) {
                SliceDecision last = out.get(out.size() - 1);
                if (start < last.end()) {
                    throw new IllegalStateException("Accepted/rejected review references overlap for the same component.");
                }
            }

            out.add(new SliceDecision(start, end, next.type()));
        }
        return out;
    }

    private Map<String, OpReviewDecision> groupByOp(List<TextOperation> logOps) {
        Map<String, OpReviewDecision> grouped = new LinkedHashMap<>();

        acceptedTextRanges.forEach((key, ranges) ->
                addTextRangesToGroupedDecision(grouped, logOps, key, ranges, true)
        );

        rejectedTextRanges.forEach((key, ranges) ->
                addTextRangesToGroupedDecision(grouped, logOps, key, ranges, false)
        );

        acceptedFormatRanges.forEach((key, ranges) ->
                grouped.computeIfAbsent(key.opId(), k -> new OpReviewDecision())
                        .acceptedFormatRanges.put(key.componentIndex(), mergeFormatRanges(ranges))
        );

        rejectedFormatRanges.forEach((key, ranges) ->
                grouped.computeIfAbsent(key.opId(), k -> new OpReviewDecision())
                        .rejectedFormatRanges.put(key.componentIndex(), mergeFormatRanges(ranges))
        );

        return grouped;
    }

    private void addTextRangesToGroupedDecision(
            Map<String, OpReviewDecision> grouped,
            List<TextOperation> logOps,
            CompKey key,
            List<CharRange> ranges,
            boolean accepted
    ) {
        if (key == null || ranges == null || ranges.isEmpty()) {
            return;
        }

        TextOperation textOp = findOp(logOps, key.opId());

        if (
                textOp == null
                        || textOp.getDelta() == null
                        || textOp.getDelta().ops == null
                        || key.componentIndex() < 0
                        || key.componentIndex() >= textOp.getDelta().ops.size()
        ) {
            return;
        }

        Op op = textOp.getDelta().ops.get(key.componentIndex());
        List<CharRange> mergedRanges = mergeRanges(ranges);

        if (mergedRanges.isEmpty()) {
            return;
        }

        OpReviewDecision decision = grouped.computeIfAbsent(key.opId(), k -> new OpReviewDecision());

        if (op.isInsert()) {
            if (accepted) {
                decision.acceptedInsertRanges.put(key.componentIndex(), mergedRanges);
            } else {
                decision.rejectedInsertRanges.put(key.componentIndex(), mergedRanges);
            }

            return;
        }

        if (op.isDelete()) {
            if (accepted) {
                decision.acceptedDeleteRanges.put(key.componentIndex(), mergedRanges);
            } else {
                decision.rejectedDeleteRanges.put(key.componentIndex(), mergedRanges);
            }
        }
    }

    private Map<String, List<CharRange>> mergeFormatRanges(Map<String, List<CharRange>> input) {
        Map<String, List<CharRange>> out = new LinkedHashMap<>();

        input.forEach((key, ranges) -> out.put(key, mergeRanges(ranges)));

        return out;
    }

    private List<CharRange> clampRanges(List<CharRange> ranges, int maxLength) {
        if (ranges == null || ranges.isEmpty() || maxLength <= 0) {
            return Collections.emptyList();
        }

        List<CharRange> clamped = new ArrayList<>();

        for (CharRange range : ranges) {
            int start = Math.max(0, Math.min(range.start(), maxLength));
            int end = Math.max(start, Math.min(range.start() + range.length(), maxLength));

            if (start < end) {
                clamped.add(new CharRange(start, end - start));
            }
        }

        return mergeRanges(clamped);
    }

    private List<CharRange> mergeRanges(List<CharRange> ranges) {
        if (ranges == null || ranges.isEmpty()) return Collections.emptyList();

        List<CharRange> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparingInt(CharRange::start));

        List<CharRange> merged = new ArrayList<>();

        for (CharRange range : sorted) {
            if (merged.isEmpty()) {
                merged.add(range);
                continue;
            }

            CharRange last = merged.get(merged.size() - 1);
            int lastEnd = last.start() + last.length();

            if (range.start() <= lastEnd) {
                int newEnd = Math.max(lastEnd, range.start() + range.length());
                merged.set(merged.size() - 1, new CharRange(last.start(), newEnd - last.start()));
            } else {
                merged.add(range);
            }
        }

        return merged;
    }

    private boolean hasEffectiveOps(Delta delta) {
        if (delta == null || delta.ops == null || delta.ops.isEmpty()) return false;

        return delta.ops.stream().anyMatch(op ->
                op.isInsert()
                        || op.isDelete()
                        || (op.isRetain() && op.getAttributes() != null && !op.getAttributes().isEmpty())
        );
    }

    private TextOperation findOp(List<TextOperation> logOps, String opId) {
        return logOps.stream()
                .filter(op -> Objects.equals(op.getOpId(), opId))
                .findFirst()
                .orElse(null);
    }

    private enum DecisionType {
        ACCEPTED,
        REJECTED
    }

    private record SliceDecision(int start, int end, DecisionType type) {}

    private static class OpReviewDecision {
        Map<Integer, List<CharRange>> acceptedInsertRanges = new HashMap<>();
        Map<Integer, List<CharRange>> rejectedInsertRanges = new HashMap<>();

        Map<Integer, List<CharRange>> acceptedDeleteRanges = new HashMap<>();
        Map<Integer, List<CharRange>> rejectedDeleteRanges = new HashMap<>();

        Map<Integer, Map<String, List<CharRange>>> acceptedFormatRanges = new HashMap<>();
        Map<Integer, Map<String, List<CharRange>>> rejectedFormatRanges = new HashMap<>();
    }

    private record SplitResult(Delta accepted, Delta rejected, Delta pending) {}

    public record ReviewApplyResult(boolean changed, Delta committedMasterDelta) {}
}