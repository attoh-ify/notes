package com.example.notes.dto.attribution;

import com.example.notes.dto.ot.Delta;
import com.example.notes.dto.ot.Op;
import com.example.notes.dto.ot.OpState;
import com.example.notes.dto.ot.TextOperation;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class AttributionCancellationAccumulator {
    private record CompKey(String opId, int componentIndex) {}
    private record CharRange(int start, int length) {}

    private final Map<CompKey, List<CharRange>> cancelledTextRanges = new LinkedHashMap<>();
    private final Map<CompKey, Map<String, List<CharRange>>> cancelledFormatRanges = new LinkedHashMap<>();

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
            cancelledFormatRanges
                    .computeIfAbsent(key, k -> new LinkedHashMap<>())
                    .computeIfAbsent(attributeKey, k -> new ArrayList<>())
                    .add(range);
            return;
        }

        cancelledTextRanges
                .computeIfAbsent(key, k -> new ArrayList<>())
                .add(range);
    }

    public boolean isEmpty() {
        return cancelledTextRanges.isEmpty()
                && cancelledFormatRanges.isEmpty();
    }

    private boolean isCancellationEmpty() {
        return cancelledTextRanges.isEmpty()
                && cancelledFormatRanges.isEmpty();
    }

    public boolean flushCancellationsAndReturnChanged(List<TextOperation> logOps) {
        if (logOps == null || logOps.isEmpty() || isCancellationEmpty()) {
            return false;
        }

        boolean changed = false;
        Map<String, OpCancellationDecision> grouped = groupByOp(logOps);

        for (Map.Entry<String, OpCancellationDecision> entry : grouped.entrySet()) {
            String opId = entry.getKey();
            OpCancellationDecision decision = entry.getValue();

            TextOperation textOp = findOp(logOps, opId);

            if (textOp == null || textOp.getDelta() == null || textOp.getDelta().ops == null) {
                continue;
            }

            SplitResult split = splitOperation(textOp.getDelta(), decision, opId);

            Delta cancelled = split.cancelled().chop();
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

    private SplitResult splitOperation(
            Delta original,
            OpCancellationDecision decision,
            String opId
    ) {
        Delta cancelled = new Delta();
        Delta pending = new Delta();

        for (int i = 0; i < original.ops.size(); i++) {
            Op op = original.ops.get(i);

            if (op.isRetain() && op.getAttributes() == null) {
                int len = (Integer) op.getRetain();
                cancelled.retain(len, null);
                pending.retain(len, null);
                continue;
            }

            else if (op.isInsert() && op.getInsert() instanceof String text) {
                List<CharRange> cancelledRanges =
                        clampRanges(decision.cancelledInsertRanges.get(i), text.length());

                Map<String, List<CharRange>> cancelledFormats =
                        decision.cancelledFormatRanges.getOrDefault(i, Collections.emptyMap());

                splitInsert(
                        text,
                        op.getAttributes(),
                        cancelledRanges,
                        cancelledFormats,
                        cancelled,
                        pending
                );

                continue;
            }

            else if (op.isInsert() && op.getInsert() != null && !(op.getInsert() instanceof String)) {
                List<CharRange> cancelledRanges =
                        clampRanges(decision.cancelledInsertRanges.get(i), 1);

                splitEmbedInsert(
                        op.getInsert(),
                        op.getAttributes(),
                        cancelledRanges,
                        cancelled,
                        pending
                );

                continue;
            }

            else if (op.isDelete()) {
                int len = op.getDelete();

                List<CharRange> cancelledRanges = clampRanges(decision.cancelledDeleteRanges.get(i), len);

                splitDelete(len, cancelledRanges, cancelled, pending);
                continue;
            }

            else if (op.isRetain() && op.getAttributes() != null) {
                int len = (Integer) op.getRetain();

                Map<String, List<CharRange>> cancelledFormats =
                        decision.cancelledFormatRanges.getOrDefault(i, Collections.emptyMap());

                splitFormatRetain(len, op.getAttributes(), cancelledFormats, cancelled, pending);
                continue;
            }

            log.warn("[REVIEW-ACC:WARN] opId={} unknown component idx={} op={}", opId, i, op);
        }

        return new SplitResult(cancelled.chop(), pending.chop());
    }

    private void splitEmbedInsert(
            Object embed,
            Map<String, Object> attrs,
            List<CharRange> cancelledRanges,
            Delta cancelled,
            Delta pending
    ) {
        List<SliceDecision> decisions =
                buildSliceDecisions(1, cancelledRanges);

        if (decisions.isEmpty()) {
            cancelled.retain(1, null);
            pending.insert(embed, attrs);
            return;
        }

        cancelled.insert(embed, attrs);
        pending.retain(1, null);
    }

    private void splitInsert(
            String text,
            Map<String, Object> attrs,
            List<CharRange> cancelledRanges,
            Map<String, List<CharRange>> cancelledFormats,
            Delta cancelled,
            Delta pending
    ) {
        Map<String, Object> safeAttrs =
                attrs != null ? new LinkedHashMap<>(attrs) : new LinkedHashMap<>();

        Map<Integer, Set<String>> cancelledFormatAt =
                buildFormatPositionMap(text.length(), cancelledFormats);

        int cursor = 0;
        List<SliceDecision> decisions =
                buildSliceDecisions(text.length(), cancelledRanges);

        for (SliceDecision decision : decisions) {
            if (decision.start() > cursor) {
                appendPendingInsertWithFormatDecisions(
                        text.substring(cursor, decision.start()),
                        cursor,
                        safeAttrs,
                        cancelledFormatAt,
                        cancelled,
                        pending
                );
            }

            String part = text.substring(decision.start(), decision.end());

            appendCancelledInsertWithFormatDecisions(
                    part,
                    decision.start(),
                    safeAttrs,
                    cancelledFormatAt,
                    cancelled,
                    pending
            );

            cursor = decision.end();
        }

        if (cursor < text.length()) {
            appendPendingInsertWithFormatDecisions(
                    text.substring(cursor),
                    cursor,
                    safeAttrs,
                    cancelledFormatAt,
                    cancelled,
                    pending
            );
        }
    }

    private void appendCancelledInsertWithFormatDecisions(
            String text,
            int absoluteStart,
            Map<String, Object> attrs,
            Map<Integer, Set<String>> cancelledFormatAt,
            Delta cancelled,
            Delta pending
    ) {
        appendInsertByFormatDecision(
                text,
                absoluteStart,
                attrs,
                cancelledFormatAt,
                true,
                cancelled,
                pending
        );
    }

    private void appendPendingInsertWithFormatDecisions(
            String text,
            int absoluteStart,
            Map<String, Object> attrs,
            Map<Integer, Set<String>> cancelledFormatAt,
            Delta cancelled,
            Delta pending
    ) {
        appendInsertByFormatDecision(
                text,
                absoluteStart,
                attrs,
                cancelledFormatAt,
                false,
                cancelled,
                pending
        );
    }

    private void appendInsertByFormatDecision(
            String text,
            int absoluteStart,
            Map<String, Object> attrs,
            Map<Integer, Set<String>> cancelledFormatAt,
            boolean textCancelled,
            Delta cancelled,
            Delta pending
    ) {
        int local = 0;

        while (local < text.length()) {
            int absoluteIndex = absoluteStart + local;

            Set<String> cancelledKeys =
                    cancelledFormatAt.getOrDefault(absoluteIndex, Collections.emptySet());

            int end = local + 1;

            while (end < text.length()) {
                int nextAbsolute = absoluteStart + end;

                if (!Objects.equals(
                        cancelledFormatAt.getOrDefault(nextAbsolute, Collections.emptySet()),
                        cancelledKeys
                )) {
                    break;
                }

                end++;
            }

            String part = text.substring(local, end);
            int len = part.length();

            Map<String, Object> cancelledAttrs = new LinkedHashMap<>(attrs);
            Map<String, Object> pendingAttrs = new LinkedHashMap<>(attrs);

            if (textCancelled) {
                for (String key : attrs.keySet()) {
                    boolean explicitlyCancelled = cancelledKeys.contains(key);

                    if (!explicitlyCancelled) {
                        cancelledAttrs.remove(key);
                    }
                }

                cancelled.insert(part, cancelledAttrs.isEmpty() ? null : cancelledAttrs);

                Map<String, Object> pendingOnlyAttrs = new LinkedHashMap<>();

                for (String key : attrs.keySet()) {
                    boolean explicitlyCancelled = cancelledKeys.contains(key);

                    if (!explicitlyCancelled) {
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
                 */
                cancelled.retain(len, null);
                pending.insert(part, pendingAttrs.isEmpty() ? null : pendingAttrs);
            }

            local = end;
        }
    }

    private void splitDelete(
            int len,
            List<CharRange> cancelledRanges,
            Delta cancelled,
            Delta pending
    ) {
        int cursor = 0;
        List<SliceDecision> decisions = buildSliceDecisions(len, cancelledRanges);

        for (SliceDecision decision : decisions) {
            if (decision.start() > cursor) {
                int pendingLen = decision.start() - cursor;
                cancelled.retain(pendingLen, null);
                pending.delete(pendingLen);
            }

            int partLen = decision.end() - decision.start();

            cancelled.delete(partLen);

            cursor = decision.end();
        }

        if (cursor < len) {
            int tailLen = len - cursor;
            cancelled.retain(tailLen, null);
            pending.delete(tailLen);
        }
    }

    private void splitFormatRetain(
            int len,
            Map<String, Object> attrs,
            Map<String, List<CharRange>> cancelledFormats,
            Delta cancelled,
            Delta pending
    ) {
        Map<Integer, Set<String>> cancelledAt = buildFormatPositionMap(len, cancelledFormats);

        int cursor = 0;

        while (cursor < len) {
            int start = cursor;
            Set<String> cancelledKeys = cancelledAt.getOrDefault(cursor, Collections.emptySet());

            while (
                    cursor < len
                            && Objects.equals(cancelledAt.getOrDefault(cursor, Collections.emptySet()), cancelledKeys)
            ) {
                cursor++;
            }

            int segLen = cursor - start;

            Map<String, Object> cancelledAttrs = pickAttrs(attrs, cancelledKeys);
            Map<String, Object> pendingAttrs = new LinkedHashMap<>(attrs);

            for (String key : cancelledKeys) pendingAttrs.remove(key);

            cancelled.retain(segLen, cancelledAttrs.isEmpty() ? null : cancelledAttrs);
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
            List<CharRange> cancelledRanges
    ) {
        List<SliceDecision> raw = new ArrayList<>();

        for (CharRange range : cancelledRanges) {
            raw.add(new SliceDecision(range.start(), range.start() + range.length()));
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
                    throw new IllegalStateException("cancelled review references overlap for the same component.");
                }
            }

            out.add(new SliceDecision(start, end));
        }
        return out;
    }

    private Map<String, OpCancellationDecision> groupByOp(List<TextOperation> logOps) {
        Map<String, OpCancellationDecision> grouped = new LinkedHashMap<>();

        cancelledTextRanges.forEach((key, ranges) ->
                addTextRangesToGroupedDecision(grouped, logOps, key, ranges)
        );

        cancelledFormatRanges.forEach((key, ranges) ->
                grouped.computeIfAbsent(key.opId(), k -> new OpCancellationDecision())
                        .cancelledFormatRanges.put(key.componentIndex(), mergeFormatRanges(ranges))
        );

        return grouped;
    }

    private void addTextRangesToGroupedDecision(
            Map<String, OpCancellationDecision> grouped,
            List<TextOperation> logOps,
            CompKey key,
            List<CharRange> ranges
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

        OpCancellationDecision decision = grouped.computeIfAbsent(key.opId(), k -> new OpCancellationDecision());

        if (op.isInsert()) {
            decision.cancelledInsertRanges.put(key.componentIndex(), mergedRanges);

            return;
        }

        if (op.isDelete()) {
            decision.cancelledDeleteRanges.put(key.componentIndex(), mergedRanges);
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

    private record SliceDecision(int start, int end) {}

    private static class OpCancellationDecision {
        Map<Integer, List<CharRange>> cancelledInsertRanges = new HashMap<>();
        Map<Integer, List<CharRange>> cancelledDeleteRanges = new HashMap<>();
        Map<Integer, Map<String, List<CharRange>>> cancelledFormatRanges = new HashMap<>();
    }

    private record SplitResult(Delta cancelled, Delta pending) {}
}