package com.example.notes.dto.attribution;

import com.example.notes.dto.ot.Delta;
import com.example.notes.dto.ot.Op;
import com.example.notes.dto.ot.OpState;
import com.example.notes.dto.ot.TextOperation;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class CancellationAccumulator {
    private record CompKey(String opId, int componentIndex) {}
    private record CharRange(int start, int length) {}

    private final Map<CompKey, List<CharRange>> insertRanges = new LinkedHashMap<>();
    private final Map<CompKey, List<CharRange>> deleteCredits = new LinkedHashMap<>();
    private final Map<CompKey, Map<String, List<CharRange>>> formatRanges = new LinkedHashMap<>();

    // ─────────────────────────────────────────────────────────────────────────
    // Record methods
    // ─────────────────────────────────────────────────────────────────────────

    public void recordInsertCancellation(
            String insertOpId,
            int insertCompIdx,
            int componentStart,
            int length
    ) {
        if (insertOpId == null || insertCompIdx < 0 || componentStart < 0 || length <= 0) {
            log.warn("[CANCEL:RECORD:SKIP] Invalid insert cancellation — opId={} compIdx={} componentStart={} length={}",
                    insertOpId, insertCompIdx, componentStart, length);
            return;
        }

        if (componentStart < 0) {
            log.error("[CANCEL:RECORD:ERR] INSERT componentStart < 0: opId={} compIdx={} componentStart={} length={}",
                    insertOpId, insertCompIdx, componentStart, length);
        }

        log.info("[CANCEL:RECORD] INSERT opId={} compIdx={} componentStart={} length={}",
                insertOpId, insertCompIdx, componentStart, length);

        insertRanges
                .computeIfAbsent(new CompKey(insertOpId, insertCompIdx), k -> new ArrayList<>())
                .add(new CharRange(componentStart, length));
    }

    public void recordDeleteCancellation(
            String deleteOpId,
            int deleteCompIdx,
            int componentStart,
            int length
    ) {
        if (deleteOpId == null || deleteCompIdx < 0 || componentStart < 0 || length <= 0) {
            log.warn("[CANCEL:RECORD:SKIP] Invalid delete cancellation — opId={} compIdx={} componentStart={} length={}",
                    deleteOpId, deleteCompIdx, componentStart, length);
            return;
        }

        if (componentStart < 0) {
            log.error("[CANCEL:RECORD:ERR] DELETE componentStart < 0: opId={} compIdx={} componentStart={} length={}",
                    deleteOpId, deleteCompIdx, componentStart, length);
        }

        log.info("[CANCEL:RECORD] DELETE_CREDIT opId={} compIdx={} componentStart={} length={}",
                deleteOpId, deleteCompIdx, componentStart, length);

        deleteCredits
                .computeIfAbsent(new CompKey(deleteOpId, deleteCompIdx), k -> new ArrayList<>())
                .add(new CharRange(componentStart, length));
    }

    public void recordFormatCancellation(
            String formatOpId,
            int formatCompIdx,
            String attributeKey,
            int componentStart,
            int length
    ) {
        if (formatOpId == null || formatCompIdx < 0 || attributeKey == null || componentStart < 0 || length <= 0) {
            log.warn("[CANCEL:RECORD:SKIP] Invalid format cancellation — opId={} compIdx={} attr={} componentStart={} length={}",
                    formatOpId, formatCompIdx, attributeKey, componentStart, length);
            return;
        }

        log.info("[CANCEL:RECORD] FORMAT opId={} compIdx={} attr={} componentStart={} length={}",
                formatOpId, formatCompIdx, attributeKey, componentStart, length);

        formatRanges
                .computeIfAbsent(new CompKey(formatOpId, formatCompIdx), k -> new LinkedHashMap<>())
                .computeIfAbsent(attributeKey, k -> new ArrayList<>())
                .add(new CharRange(componentStart, length));
    }

    public boolean isEmpty() {
        return insertRanges.isEmpty() && formatRanges.isEmpty() && deleteCredits.isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Flush
    // ─────────────────────────────────────────────────────────────────────────

    public boolean flushAndReturnChanged(List<TextOperation> logOps) {
        if (logOps == null || logOps.isEmpty() || isEmpty()) {
            log.debug("[CANCEL:FLUSH] skipping flush — logOps={} empty={}", logOps == null ? "null" : logOps.size(), isEmpty());
            return false;
        }

        log.info("[CANCEL:FLUSH] START logOps={} insertRangeKeys={} deleteCreditKeys={} formatRangeKeys={}",
                logOps.size(), insertRanges.size(), deleteCredits.size(), formatRanges.size());
        logRevisionLogSnapshot("BEFORE_FLUSH", logOps);

        boolean changed = false;
        Map<String, OpCancellationDelta> grouped = groupByOp();

        log.info("[CANCEL:FLUSH] groupByOp produced {} opIds: {}", grouped.size(), grouped.keySet());

        for (Map.Entry<String, OpCancellationDelta> entry : grouped.entrySet()) {
            String opId = entry.getKey();
            OpCancellationDelta data = entry.getValue();

            log.info("[CANCEL:FLUSH] processing opId={} data={}", opId, data);

            TextOperation textOp = findOp(logOps, opId);

            if (textOp == null) {
                log.warn("[CANCEL:FLUSH:WARN] Text op not found in revision log for opId={}. logOp opIds={}",
                        opId, logOps.stream().map(TextOperation::getOpId).toList());
                continue;
            }

            Delta original = textOp.getDelta();

            if (original == null || original.ops == null || original.ops.isEmpty()) {
                log.warn("[CANCEL:FLUSH:WARN] opId={} has null/empty delta — skipping", opId);
                continue;
            }

            log.info("[CANCEL:FLUSH] opId={} original delta={}", opId, original);

            SplitDelta split = buildCommittedAndRemaining(original, data, opId);

            Delta committed = split.committed().chop();
            Delta remaining = split.remaining().chop();

            log.info("[CANCEL:FLUSH] opId={} committed={}", opId, committed);
            log.info("[CANCEL:FLUSH] opId={} remaining={}", opId, remaining);

            if (deltaEquals(original.chop(), remaining)) {
                log.info("[CANCEL:FLUSH:NO-OP] opId={} — cancellation produced no change (remaining == original)", opId);
                continue;
            }

            boolean hasRemaining = !remaining.ops.isEmpty()
                    && remaining.ops.stream().anyMatch(
                    o -> o.isInsert()
                            || o.isDelete()
                            || (o.isRetain() && o.getAttributes() != null && !o.getAttributes().isEmpty())
            );

            log.info("[CANCEL:FLUSH] opId={} hasRemaining={}", opId, hasRemaining);

            if (!hasRemaining) {
                log.info("[CANCEL:FLUSH] opId={} marking DEAD (no effective ops remain)", opId);
                textOp.setState(OpState.DEAD);
                changed = true;
                continue;
            }

            TextOperation committedOp = new TextOperation(
                    committed,
                    textOp.getActorEmail(),
                    textOp.getRevision(),
                    OpState.DEAD,
                    textOp.getCreatedAt()
            );

            int insertIdx = logOps.indexOf(textOp);
            textOp.setDelta(remaining);
            logOps.add(insertIdx, committedOp);

            log.info("[CANCEL:FLUSH] opId={} split into DEAD(committed) at idx={} and live(remaining)", opId, insertIdx);
            changed = true;
        }

        if (changed) {
            logRevisionLogSnapshot("AFTER_FLUSH", logOps);
        } else {
            log.info("[CANCEL:FLUSH] no changes made to revision log");
        }

        return changed;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // buildCommittedAndRemaining
    // ─────────────────────────────────────────────────────────────────────────

    private SplitDelta buildCommittedAndRemaining(
            Delta original,
            OpCancellationDelta data,
            String opId
    ) {
        Delta committed = new Delta();
        Delta remaining = new Delta();

        log.debug("[CANCEL:BUILD] opId={} building split for {} components", opId, original.ops.size());

        for (int i = 0; i < original.ops.size(); i++) {
            Op op = original.ops.get(i);
            log.debug("[CANCEL:BUILD] opId={} compIdx={} op={}", opId, i, op);

            // ── plain retain ──────────────────────────────────────────────────
            if (op.isRetain() && op.getAttributes() == null) {
                int len = (Integer) op.getRetain();
                committed.retain(len, null);
                remaining.retain(len, null);
                log.debug("[CANCEL:BUILD] opId={} compIdx={} plain retain len={} — unchanged in both", opId, i, len);
                continue;
            }

            // ── insert ────────────────────────────────────────────────────────
            if (op.isInsert() && op.getInsert() instanceof String text) {
                List<CharRange> cancelled = clampRanges(data.insertRanges.get(i), text.length(), opId, i, "insert");

                log.debug("[CANCEL:BUILD] opId={} compIdx={} INSERT text.len={} cancelledRanges={}",
                        opId, i, text.length(), cancelled);

                if (cancelled.isEmpty()) {
                    committed.retain(text.length(), null);
                    remaining.insert(text, op.getAttributes());
                    log.debug("[CANCEL:BUILD] opId={} compIdx={} INSERT no cancellation — fully live", opId, i);
                    continue;
                }

                int cursor = 0;

                for (CharRange r : cancelled) {
                    int start = r.start();
                    int end = start + r.length();

                    if (start > cursor) {
                        String kept = text.substring(cursor, start);
                        log.debug("[CANCEL:BUILD] opId={} compIdx={} INSERT keeping '{}' [{}–{}]", opId, i, kept, cursor, start);
                        committed.retain(kept.length(), null);
                        remaining.insert(kept, op.getAttributes());
                    }

                    String cancelledText = text.substring(start, end);
                    log.debug("[CANCEL:BUILD] opId={} compIdx={} INSERT cancelling '{}' [{}–{}]", opId, i, cancelledText, start, end);
                    committed.insert(cancelledText, op.getAttributes());

                    cursor = end;
                }

                if (cursor < text.length()) {
                    String tail = text.substring(cursor);
                    log.debug("[CANCEL:BUILD] opId={} compIdx={} INSERT tail '{}' [{}–{}]", opId, i, tail, cursor, text.length());
                    committed.retain(tail.length(), null);
                    remaining.insert(tail, op.getAttributes());
                }

                // Validate: total live text in remaining must equal original text length
                int remainingInsertLen = remaining.ops.stream()
                        .filter(Op::isInsert)
                        .filter(o -> o.getInsert() instanceof String)
                        .mapToInt(o -> ((String) o.getInsert()).length())
                        .sum();
                log.debug("[CANCEL:BUILD] opId={} compIdx={} INSERT validation: original.len={} cancelled.total={} live.total={}",
                        opId, i, text.length(),
                        cancelled.stream().mapToInt(CharRange::length).sum(),
                        text.length() - cancelled.stream().mapToInt(CharRange::length).sum());

                continue;
            }

            // ── retain with attrs (format) ────────────────────────────────────
            if (op.isRetain() && op.getAttributes() != null) {
                int len = (Integer) op.getRetain();
                Map<String, List<CharRange>> attrRanges = data.formatRanges.get(i);

                log.debug("[CANCEL:BUILD] opId={} compIdx={} FORMAT retain len={} attrRanges={}",
                        opId, i, len, attrRanges);

                if (attrRanges == null || attrRanges.isEmpty()) {
                    committed.retain(len, op.getAttributes());
                    remaining.retain(len, op.getAttributes());
                    log.debug("[CANCEL:BUILD] opId={} compIdx={} FORMAT no cancellation — unchanged in both", opId, i);
                    continue;
                }

                Map<Integer, Set<String>> cancelMap = new HashMap<>();

                for (Map.Entry<String, List<CharRange>> e : attrRanges.entrySet()) {
                    String attrKey = e.getKey();

                    if (!op.getAttributes().containsKey(attrKey)) {
                        log.warn("[CANCEL:BUILD:WARN] opId={} compIdx={} FORMAT attr='{}' not present in component attrs={} — skipping cancellation for this attr",
                                opId, i, attrKey, op.getAttributes().keySet());
                        continue;
                    }

                    List<CharRange> clamped = clampRanges(e.getValue(), len, opId, i, "format:" + attrKey);
                    log.debug("[CANCEL:BUILD] opId={} compIdx={} FORMAT attr={} cancelledRanges={}", opId, i, attrKey, clamped);

                    for (CharRange r : clamped) {
                        for (int p = r.start(); p < r.start() + r.length(); p++) {
                            cancelMap.computeIfAbsent(p, x -> new HashSet<>()).add(attrKey);
                        }
                    }
                }

                log.debug("[CANCEL:BUILD] opId={} compIdx={} FORMAT cancelMap positions={}",
                        opId, i, cancelMap.isEmpty() ? "none" : cancelMap.size() + " positions");

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
                        log.debug("[CANCEL:BUILD] opId={} compIdx={} FORMAT seg [{}–{}] len={} — live in both",
                                opId, i, start, cursor, segLen);
                        committed.retain(segLen, null);
                        remaining.retain(segLen, base);
                    } else {
                        Map<String, Object> remainingAttrs = new LinkedHashMap<>(base);
                        for (String k : keys) remainingAttrs.remove(k);

                        log.debug("[CANCEL:BUILD] opId={} compIdx={} FORMAT seg [{}–{}] len={} cancelledKeys={} remainingAttrs={}",
                                opId, i, start, cursor, segLen, keys, remainingAttrs);

                        committed.retain(segLen, base);
                        remaining.retain(segLen, remainingAttrs.isEmpty() ? null : remainingAttrs);
                    }
                }

                continue;
            }

            // ── delete ────────────────────────────────────────────────────────
            if (op.isDelete()) {
                int len = op.getDelete();
                List<CharRange> credited = clampRanges(data.deleteCredits.get(i), len, opId, i, "delete-credit");

                log.debug("[CANCEL:BUILD] opId={} compIdx={} DELETE len={} creditedRanges={}", opId, i, len, credited);

                if (credited.isEmpty()) {
                    committed.retain(len, null);
                    remaining.delete(len);
                    log.debug("[CANCEL:BUILD] opId={} compIdx={} DELETE no credit — fully active", opId, i);
                    continue;
                }

                int cursor = 0;

                for (CharRange r : credited) {
                    int start = r.start();
                    int end = start + r.length();

                    if (start > cursor) {
                        int activeLen = start - cursor;
                        log.debug("[CANCEL:BUILD] opId={} compIdx={} DELETE keeping active [{}–{}] len={}",
                                opId, i, cursor, start, activeLen);
                        committed.retain(activeLen, null);
                        remaining.delete(activeLen);
                    }

                    log.debug("[CANCEL:BUILD] opId={} compIdx={} DELETE credited [{}–{}] len={} — dead in both",
                            opId, i, start, end, r.length());
                    committed.delete(r.length());

                    cursor = end;
                }

                if (cursor < len) {
                    int tailLen = len - cursor;
                    log.debug("[CANCEL:BUILD] opId={} compIdx={} DELETE tail [{}–{}] len={} — active",
                            opId, i, cursor, len, tailLen);
                    committed.retain(tailLen, null);
                    remaining.delete(tailLen);
                }

                // Validate: credited total + active total == original delete length
                int creditedTotal = credited.stream().mapToInt(CharRange::length).sum();
                log.debug("[CANCEL:BUILD] opId={} compIdx={} DELETE validation: original.len={} credited={} active={}",
                        opId, i, len, creditedTotal, len - creditedTotal);

                continue;
            }

            log.warn("[CANCEL:BUILD:WARN] opId={} compIdx={} unrecognized op type — op={}", opId, i, op);
        }

        SplitDelta result = new SplitDelta(committed.chop(), remaining.chop());
        log.debug("[CANCEL:BUILD] opId={} result: committed={} remaining={}", opId, result.committed(), result.remaining());
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // groupByOp
    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, OpCancellationDelta> groupByOp() {
        Map<String, OpCancellationDelta> map = new LinkedHashMap<>();

        insertRanges.forEach((k, v) -> {
            List<CharRange> merged = mergeRanges(v);
            log.debug("[CANCEL:GROUP] INSERT opId={} compIdx={} rawRanges={} mergedRanges={}", k.opId(), k.componentIndex(), v, merged);
            map.computeIfAbsent(k.opId(), x -> new OpCancellationDelta())
                    .insertRanges.put(k.componentIndex(), merged);
        });

        formatRanges.forEach((k, v) -> {
            Map<String, List<CharRange>> mergedRanges = new LinkedHashMap<>();
            v.forEach((attrKey, ranges) -> {
                List<CharRange> merged = mergeRanges(ranges);
                log.debug("[CANCEL:GROUP] FORMAT opId={} compIdx={} attr={} rawRanges={} mergedRanges={}",
                        k.opId(), k.componentIndex(), attrKey, ranges, merged);
                mergedRanges.put(attrKey, merged);
            });
            map.computeIfAbsent(k.opId(), x -> new OpCancellationDelta())
                    .formatRanges.put(k.componentIndex(), mergedRanges);
        });

        deleteCredits.forEach((k, v) -> {
            List<CharRange> merged = mergeRanges(v);
            log.debug("[CANCEL:GROUP] DELETE_CREDIT opId={} compIdx={} rawRanges={} mergedRanges={}", k.opId(), k.componentIndex(), v, merged);
            map.computeIfAbsent(k.opId(), x -> new OpCancellationDelta())
                    .deleteCredits.put(k.componentIndex(), merged);
        });

        log.debug("[CANCEL:GROUP] grouped {} opIds", map.size());
        return map;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Range utilities
    // ─────────────────────────────────────────────────────────────────────────

    private static List<CharRange> clampRanges(
            List<CharRange> ranges,
            int maxLength,
            String opId,
            int compIdx,
            String type
    ) {
        if (ranges == null || ranges.isEmpty() || maxLength <= 0) {
            log.debug("[CANCEL:CLAMP] {} opId={} compIdx={} — no ranges to clamp", type, opId, compIdx);
            return Collections.emptyList();
        }

        List<CharRange> clamped = new ArrayList<>();

        for (CharRange r : ranges) {
            if (r == null || r.length() <= 0) continue;

            int start = Math.max(0, r.start());
            int end = Math.min(maxLength, r.start() + r.length());

            if (start >= end) {
                log.warn("[CANCEL:CLAMP:WARN] {} opId={} compIdx={} range={} is fully outside [0,{}] — discarding",
                        type, opId, compIdx, r, maxLength);
                continue;
            }

            if (start != r.start() || end != r.start() + r.length()) {
                log.warn("[CANCEL:CLAMP:CLAMP] {} opId={} compIdx={} range={} clamped to [{},{}] (maxLength={})",
                        type, opId, compIdx, r, start, end, maxLength);
            }

            clamped.add(new CharRange(start, end - start));
        }

        List<CharRange> merged = mergeRanges(clamped);
        log.debug("[CANCEL:CLAMP] {} opId={} compIdx={} maxLength={} input={} clamped={} merged={}",
                type, opId, compIdx, maxLength, ranges, clamped, merged);

        // Sanity: no range should exceed maxLength
        for (CharRange r : merged) {
            if (r.start() + r.length() > maxLength) {
                log.error("[CANCEL:CLAMP:ERR] {} opId={} compIdx={} merged range [{},+{}] exceeds maxLength={}",
                        type, opId, compIdx, r.start(), r.length(), maxLength);
            }
            if (r.start() < 0) {
                log.error("[CANCEL:CLAMP:ERR] {} opId={} compIdx={} merged range has negative start={}", type, opId, compIdx, r.start());
            }
        }

        return merged;
    }

    private static List<CharRange> mergeRanges(List<CharRange> ranges) {
        if (ranges == null || ranges.isEmpty()) return Collections.emptyList();

        List<CharRange> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparingInt(CharRange::start));

        List<CharRange> merged = new ArrayList<>();

        for (CharRange r : sorted) {
            if (merged.isEmpty()) {
                merged.add(r);
                continue;
            }

            CharRange last = merged.get(merged.size() - 1);
            int lastEnd = last.start() + last.length();

            if (r.start() <= lastEnd) {
                int newEnd = Math.max(lastEnd, r.start() + r.length());
                log.debug("[CANCEL:MERGE] merging range {} with last {}", r, last);
                merged.set(merged.size() - 1, new CharRange(last.start(), newEnd - last.start()));
            } else {
                merged.add(r);
            }
        }

        return merged;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static boolean deltaEquals(Delta a, Delta b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return Objects.equals(a.toString(), b.toString());
    }

    private static TextOperation findOp(List<TextOperation> logOps, String opId) {
        return logOps.stream()
                .filter(op -> op.getOpId().equals(opId))
                .findFirst()
                .orElse(null);
    }

    private static void logRevisionLogSnapshot(String label, List<TextOperation> logOps) {
        if (logOps == null) {
            log.info("[CANCEL:REVLOG:{}] null", label);
            return;
        }

        log.info("[CANCEL:REVLOG:{}] size={}", label, logOps.size());

        for (int i = 0; i < logOps.size(); i++) {
            TextOperation op = logOps.get(i);

            if (op == null) {
                log.info("[CANCEL:REVLOG:{}] #{} null", label, i);
                continue;
            }

            log.info("[CANCEL:REVLOG:{}] #{} opId={} state={} revision={} actor={} delta={}",
                    label, i,
                    op.getOpId(),
                    op.getState(),
                    op.getRevision(),
                    op.getActorEmail(),
                    op.getDelta());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inner classes
    // ─────────────────────────────────────────────────────────────────────────

    private static class OpCancellationDelta {
        Map<Integer, List<CharRange>> insertRanges = new HashMap<>();
        Map<Integer, Map<String, List<CharRange>>> formatRanges = new HashMap<>();
        Map<Integer, List<CharRange>> deleteCredits = new HashMap<>();

        @Override
        public String toString() {
            return "OpCancellationDelta{" +
                    "insertRanges=" + insertRanges +
                    ", formatRanges=" + formatRanges +
                    ", deleteCredits=" + deleteCredits +
                    '}';
        }
    }

    record SplitDelta(Delta committed, Delta remaining) {}
}