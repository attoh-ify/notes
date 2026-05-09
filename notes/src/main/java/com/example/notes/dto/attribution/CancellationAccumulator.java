package com.example.notes.dto.attribution;

import com.example.notes.dto.ot.Delta;
import com.example.notes.dto.ot.Op;
import com.example.notes.dto.ot.OpState;
import com.example.notes.dto.ot.TextOperation;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class CancellationAccumulator {
    // Key type
    private record CompKey(String opId, int componentIndex) {}
    private record CharRange(int start, int length) {}

    // State
    private final Map<CompKey, List<CharRange>> insertRanges = new LinkedHashMap<>();
    private final Map<CompKey, Integer> deleteCredits = new LinkedHashMap<>();
    private final Map<CompKey, Map<String, List<CharRange>>> formatRanges = new LinkedHashMap<>();

    public void recordInsertCancellation(
            String insertOpId,
            int insertCompIdx,
            int componentStart,
            int length
    ) {
        if (length <= 0) return;

        insertRanges
                .computeIfAbsent(new CompKey(insertOpId, insertCompIdx), k -> new ArrayList<>())
                .add(new CharRange(componentStart, length));
    }

    public void recordDeleteCancellation(
            String deleteOpId,
            int deleteCompIdx,
            int length
    ) {
        if (length <= 0) return;

        deleteCredits.merge(new CompKey(deleteOpId, deleteCompIdx), length, Integer::sum);
    }

    public void recordFormatCancellation(
            String formatOpId,
            int formatCompIdx,
            String attributeKey,
            int componentStart,
            int length
    ) {
        if (length <= 0) return;

        formatRanges
                .computeIfAbsent(new CompKey(formatOpId, formatCompIdx), k -> new LinkedHashMap<>())
                .computeIfAbsent(attributeKey, k -> new ArrayList<>())
                .add(new CharRange(componentStart, length));
    }

    public boolean isEmpty() {
        return insertRanges.isEmpty() && formatRanges.isEmpty() && deleteCredits.isEmpty();
    }

    public void flush(List<TextOperation> logOps) {
        Map<String, OpCancellationDelta> grouped = groupByOp();

        for (Map.Entry<String, OpCancellationDelta> entry : grouped.entrySet()) {
            String opId = entry.getKey();
            OpCancellationDelta data = entry.getValue();

            TextOperation textOp = findOp(logOps, opId);
            if (textOp == null) {
                log.warn("[FLUSH] Text op not found: {}", opId);
                continue;
            }

            Delta original = textOp.getDelta();

            SplitDelta split = buildCommittedAndRemaining(original, data);
            Delta committed = split.committed;
            Delta remaining = split.remaining;
            remaining = remaining.chop();
            committed = committed.chop();
            System.out.println("original: " + original.toString());
            System.out.println("opId: " + opId);
            System.out.println("data: " + data.toString());
            System.out.println("Committed: " + committed.toString());
            System.out.println("Remaining: " + remaining.toString());

            boolean hasRemaining = !remaining.ops.isEmpty() &&
                    remaining.ops.stream().anyMatch(
                            o -> o.isInsert()
                                    || o.isDelete()
                                    || (o.isRetain()
                                    && o.getAttributes() != null
                                    && !o.getAttributes().isEmpty())
                    );

            if (!hasRemaining) {
                textOp.setState(OpState.DEAD);
                continue;
            }

            TextOperation committedOp = new TextOperation(
                    committed,
                    textOp.getActorEmail(),
                    textOp.getRevision(),
                    OpState.DEAD,
                    textOp.getCreatedAt()
            );

            textOp.setDelta(remaining);

            logOps.add(logOps.indexOf(textOp), committedOp);
        }
    }

    private SplitDelta buildCommittedAndRemaining(Delta original, OpCancellationDelta data) {
        Delta committed = new Delta();
        Delta remaining = new Delta();

        for (int i = 0; i < original.ops.size(); i++) {
            Op op = original.ops.get(i);

            if (op.isRetain() && op.getAttributes() == null) {
                int len = (Integer) op.getRetain();

                committed.retain(len, null);
                remaining.retain(len, null);
            } else if (op.isInsert() && op.getInsert() instanceof String text) {
                List<CharRange> cancelled = data.insertRanges.get(i);

                if (cancelled == null || cancelled.isEmpty()) {
                    committed.retain(text.length(), null);
                    remaining.insert(text, op.getAttributes());
                    continue;
                }

                List<CharRange> ranges = mergeRanges(cancelled);

                int cursor = 0;

                for (CharRange r : ranges) {
                    int start = r.start();
                    int end = start + r.length();

                    // untouched prefix → goes to remaining (since it was NOT cancelled)
                    if (start > cursor) {
                        String kept = text.substring(cursor, start);
                        committed.retain(kept.length(), null);
                        remaining.insert(kept, op.getAttributes());
                    }

                    // cancelled segment → goes to committed (because it's removed from final text)
                    String cancelledText = text.substring(start, end);
                    committed.insert(cancelledText, op.getAttributes());

                    cursor = end;
                }

                // tail
                if (cursor < text.length()) {
                    String tail = text.substring(cursor);

                    committed.retain(tail.length(), null);
                    remaining.insert(tail, op.getAttributes());
                }
            } else if (op.isRetain() && op.getAttributes() != null) {
                int len = (Integer) op.getRetain();
                Map<String, List<CharRange>> attrRanges = data.formatRanges.get(i);

                if (attrRanges == null || attrRanges.isEmpty()) {
                    committed.retain(len, op.getAttributes());
                    remaining.retain(len, op.getAttributes());
                    continue;
                }

                Map<Integer, Set<String>> cancelMap = new HashMap<>();

                for (var e : attrRanges.entrySet()) {
                    for (CharRange r : mergeRanges(e.getValue())) {
                        for (int p = r.start(); p < r.start() + r.length(); p++) {
                            cancelMap.computeIfAbsent(p, x -> new HashSet<>()).add(e.getKey());
                        }
                    }
                }

                Map<String, Object> base = op.getAttributes();

                int cursor = 0;

                while (cursor < len) {
                    int start = cursor;
                    Set<String> keys = cancelMap.get(cursor);

                    while (cursor < len && Objects.equals(cancelMap.get(cursor), keys)) {
                        cursor++;
                    }

                    int segLen = cursor - start;

                    if (keys == null || keys.isEmpty()) {
                        // Not cancelled — stays fully pending; committed skips with plain retain
                        committed.retain(segLen, null);
                        remaining.retain(segLen, base);
                    } else {
                        Map<String, Object> removed = new LinkedHashMap<>(base);
                        for (String k : keys) removed.remove(k);

                        committed.retain(segLen, removed.isEmpty() ? null : removed);
                        remaining.retain(segLen, base);
                    }
                }
            } else if (op.isDelete()) {
                int len = op.getDelete();
                int credit = data.deleteCredits.getOrDefault(i, 0);

                int commitLen = Math.min(len, credit);

                if (commitLen > 0) {
                    // Credited chars: committed as delete (they cancelled pending inserts)
                    // remaining skips them with plain retain — they're gone from the logical doc
                    committed.delete(commitLen);
                    remaining.retain(commitLen, null);
                }

                if (commitLen < len) {
                    // Leftover chars: still a real pending delete
                    // committed skips with plain retain (positional skip)
                    committed.retain(len - commitLen, null);
                    remaining.delete(len - commitLen);
                }
            }
        }

        return new SplitDelta(committed.chop(), remaining.chop());
    }

    private Map<String, OpCancellationDelta> groupByOp() {
        Map<String, OpCancellationDelta> map = new LinkedHashMap<>();

        insertRanges.forEach((k, v) -> {
            map.computeIfAbsent(k.opId(), x -> new OpCancellationDelta())
                    .insertRanges.put(k.componentIndex(), mergeRanges(v));
        });

        formatRanges.forEach((k, v) -> {
            Map<String, List<CharRange>> mergedRanges = new LinkedHashMap<>();

            v.forEach((attrKey, ranges) -> {
                mergedRanges.put(attrKey, mergeRanges(ranges));
            });

            map.computeIfAbsent(k.opId(), x -> new OpCancellationDelta())
                    .formatRanges.put(k.componentIndex(), mergedRanges);
        });

        deleteCredits.forEach((k, v) -> {
            map.computeIfAbsent(k.opId(), x -> new OpCancellationDelta())
                    .deleteCredits.put(k.componentIndex(), v);
        });

        return map;
    }

    private static List<CharRange> mergeRanges(List<CharRange> ranges) {
        if (ranges == null || ranges.isEmpty()) return Collections.emptyList();

        List<CharRange> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparingInt(CharRange::start));

        List<CharRange> merged = new ArrayList<>();
        for (CharRange r : sorted) {
            if (merged.isEmpty()) {
                merged.add(r);
            } else {
                CharRange last = merged.get(merged.size() - 1);
                int lastEnd = last.start() + last.length();
                if (r.start() <= lastEnd) {
                    int newEnd = Math.max(lastEnd, r.start() + r.length());
                    merged.set(merged.size() - 1, new CharRange(last.start(), newEnd - last.start()));
                } else {
                    merged.add(r);
                }
            }
        }
        return merged;
    }

    private static TextOperation findOp(List<TextOperation> logOps, String opId) {
        return logOps.stream().filter(op -> op.getOpId().equals(opId)).findFirst().orElse(null);
    }

    private static class OpCancellationDelta {
        Map<Integer, List<CharRange>> insertRanges = new HashMap<>();
        Map<Integer, Map<String, List<CharRange>>> formatRanges = new HashMap<>();
        Map<Integer, Integer> deleteCredits = new HashMap<>();

        @Override
        public String toString() {
            return "OpCancellationDelta{" +
                    "insertRanges=" + insertRanges +
                    ", formatRanges=" + formatRanges +
                    ", deleteCredits=" + deleteCredits +
                    '}';
        }
    }

    record SplitDelta(Delta committed, Delta remaining) {};
}