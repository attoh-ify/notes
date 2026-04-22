package com.example.notes.services.impl;

import com.example.notes.dto.attribution.*;
import com.example.notes.dto.note.CancelFormatPayload;
import com.example.notes.dto.note.CancelInsertPayload;
import com.example.notes.dto.note.NoteDto;
import com.example.notes.dto.note.OpReference;
import com.example.notes.dto.noteVersion.NoteVersionDto;
import com.example.notes.dto.ot.Delta;
import com.example.notes.dto.ot.Op;
import com.example.notes.dto.ot.OpState;
import com.example.notes.dto.ot.TextOperation;
import com.example.notes.exceptions.BadRequestException;
import com.example.notes.services.AttributionService;
import com.example.notes.services.NoteService;
import com.example.notes.services.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


import java.util.*;
import java.util.stream.Collectors;

import static com.example.notes.utils.AttributionHelpers.*;

// ─── AttributionServiceImpl.java ──────────────────────────────────────────────
//
// Converts a list of COMMITTED textOps and PENDING textOps into a ReviewProjection
// containing:
//   - visualDelta        : a Quill-compatible delta for the frontend to render
//   - formatSuggestions  : all format suggestion groups for the sidebar panel
//
// The algorithm follows five steps:
//
//   STEP 1 — SEED
//     Compose all committed ops into one base delta, then break it into ReviewRuns
//     (one run per text segment, one per newline character).
//
//   STEP 2 — APPLY PENDING OPS
//     For each pending op, walk its delta components:
//       - Plain retain   → advance cursor, maybe bridge a format group across a newline
//       - Format retain  → apply formatting to runs; create/extend format suggestion groups;
//                          detect and cancel existing format suggestions when the new format
//                          restores the pre-suggestion state
//       - Insert         → splice new runs with insert-suggestion metadata;
//                          handle inherited attributes from neighboring actors
//       - Delete         → mark runs as deleted (still visible for review);
//                          cancel insert+delete overlaps via the split API
//
//   STEP 3 — PREVIEW TEXTS
//     Compute a short preview string for each format suggestion group so the
//     sidebar can display it without requiring the frontend to parse the delta.
//
//   STEP 4 — FLUSH CANCELLATIONS
//     Persist any queued format cancellation records to the backend split API.
//
//   STEP 5 — BUILD OUTPUT
//     Apply format suggestion attrs onto cloned runs, then build the visual delta.
//
// Dependencies:
//   - TextOperation   : your existing domain class holding opId, actorEmail, createdAt,
//                       and a Delta (list of Op components)
//   - Delta / Op      : your existing Quill delta model (io.github.quilldev or similar)
//   - ReviewApiClient : a component/bean that wraps apiFetch() for the split endpoints
//   - ObjectMapper    : Jackson for attribute JSON serialisation
// ──────────────────────────────────────────────────────────────────────────────

@Service
public class AttributionServiceImpl implements AttributionService {
    private final NotePolicyService notePolicyService;
    private final RedisService redisService;
    private final NoteService noteService;

    private static final Logger log =
            LoggerFactory.getLogger(AttributionServiceImpl.class);

    public AttributionServiceImpl(NotePolicyService notePolicyService, RedisService redisService, NoteService noteService) {
        this.notePolicyService = notePolicyService;
        this.redisService = redisService;
        this.noteService = noteService;
    }

    // ─── buildReviewProjection ────────────────────────────────────────────────
    //
    // Main entry point. Mirrors buildReviewProjection() in attribution.ts exactly.
    //
    // @param noteId        used in split-API calls during delete/format cancellation
    // @param committedTextOps  ops in COMMITTED state — form the base document
    // @param pendingTextOps    ops in PENDING state — the changes under review
    // @return              ReviewProjection with visualDelta + formatSuggestions
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public ReviewProjection buildReviewProjection(
            String actorEmail, UUID noteId
    ) {
        log.info("\n{}", "=".repeat(60));
        log.info("[REVIEW_BUILD] START — noteId={}", noteId);

        notePolicyService.validateOwner(actorEmail, noteId);

        NoteDto note = redisService.getNote(noteId);

        List<TextOperation> committedTextOps = note.revisionLog().stream()
                .filter(textOp -> textOp.getState().equals(OpState.COMMITTED))
                .toList();
        List<TextOperation> pendingTextOps = note.revisionLog().stream()
                .filter(textOp -> textOp.getState().equals(OpState.PENDING))
                .toList();
        
        log.info("[REVIEW_BUILD] committedOpCount={} pendingOpCount={}", committedTextOps.size(), pendingTextOps.size());
        
        // Reset the group ID counter so IDs are predictable (g_1, g_2...) for each build
        resetGroupCounter();

        // ── STEP 1: Seed runs from committed document ──────────────────────────

        // Compose all committed ops into one base delta representing the current
        // committed document state. compose() applies each op on top of the previous.
        Delta committedDelta = new Delta();
        for (TextOperation textOp : committedTextOps) {
            committedDelta = committedDelta.compose(new Delta(textOp.getDelta().ops));
        }
        log.info("[REVIEW_BUILD] Composed committed delta — opCount={}", committedDelta.ops.size());

        // Break the committed delta into individual runs. Text segments are split on
        // "\n" so that each newline becomes its own run — required because Quill treats
        // "\n" as a paragraph terminator carrying block-level formatting.
        List<ReviewRun> runs = new ArrayList<>();
        int seedPos = 0;

        for (int idx = 0; idx < committedDelta.ops.size(); idx++) {
            Op op = committedDelta.ops.get(idx);
            if (!(op.getInsert() instanceof String insertStr)) continue;

            Map<String, Object> opAttrs = op.getAttributes() != null
                    ? new LinkedHashMap<>(op.getAttributes())
                    : new LinkedHashMap<>();

            String[] parts = insertStr.split("\n", -1); // -1 keeps trailing empty strings
            for (int i = 0; i < parts.length; i++) {
                if (!parts[i].isEmpty()) {
                    runs.add(ReviewRun.builder()
                            .text(parts[i])
                            .baseAttributes(new LinkedHashMap<>(opAttrs))
                            .suggestionAttributes(new LinkedHashMap<>())
                            .logicalStart(seedPos)
                            .opId("")            // Empty = came from committed state
                            .insertComponentIndex(idx)
                            .build());
                    seedPos += parts[i].length();
                }
                if (i < parts.length - 1) {
                    // Insert the newline separator run
                    runs.add(ReviewRun.builder()
                            .text("\n")
                            .baseAttributes(new LinkedHashMap<>())
                            .suggestionAttributes(new LinkedHashMap<>())
                            .logicalStart(seedPos)
                            .opId("")
                            .insertComponentIndex(idx)
                            .build());
                    seedPos += 1;
                }
            }
        }
        log.info("[REVIEW_BUILD] Seeded {} committed run(s). Total logical length={}", runs.size(), seedPos);

        // ── STEP 2: Apply pending ops ──────────────────────────────────────────

        List<FormatSuggestionItem> formatSuggestions = new ArrayList<>();

        // Accumulate format cancellations to flush after the run loop.
        // We defer API calls so we don't interleave async I/O with the synchronous
        // run-mutation pipeline.
        List<PendingFormatCancellation> pendingFormatCancellations = new ArrayList<>();

        for (TextOperation textOp : pendingTextOps) {
            String opId = textOp.getOpId();
            String authorEmail = textOp.getActorEmail();
            String createdAt = textOp.getCreatedAt().toString();

            log.info("\n[REVIEW_BUILD] --- Processing pending op opId={} actor={} componentCount={}",
                    opId, authorEmail, textOp.getDelta().ops.size());

            // Per-op group tracking — allows consecutive insert/delete/format components
            // to be merged into one suggestion group.
            int localLogPos = 0;
            InsertSuggestion currentInsertGroup = null;
            DeleteSuggestion currentDeleteGroup = null;
            FormatSuggestionItem currentFormatGroup = null;

            // A "bridge" allows a format group to survive across a newline-only plain retain.
            // Without it, [format-retain][plain-retain "\n"][format-retain] would produce
            // two separate format groups instead of one multi-paragraph group.
            Map<String, String> pendingFormatBridge = null; // keys: actorEmail, attributes, groupId

            List<Op> components = textOp.getDelta().ops;
            for (int compIdx = 0; compIdx < components.size(); compIdx++) {
                Op component = components.get(compIdx);

                // ── Plain retain (no attributes) ──────────────────────────────
                // Cursor advancing over existing content without changing it.
                // Resets insert/delete group state.
                if (component.isRetain() && component.getAttributes() == null) {
                    int retainLen = (int) component.getRetain();
                    boolean isLastOp = (compIdx == components.size() - 1);

                    log.debug("[RETAIN_PLAIN] opId={} compIdx={} retain={} localLogPos={} isLastOp={}",
                            opId, compIdx, retainLen, localLogPos, isLastOp);

                    // The trailing retain that Quill appends to every delta (to fill out
                    // the document length) has no semantic meaning — skip it.
                    if (isLastOp) {
                        log.debug("[RETAIN_PLAIN] opId={} compIdx={} — last op, breaking early", opId, compIdx);
                        break;
                    }

                    currentInsertGroup = null;
                    currentDeleteGroup = null;

                    boolean newlineOnly = isOnlyNewlineRetain(runs, localLogPos, retainLen);
                    log.debug("[RETAIN_PLAIN] opId={} compIdx={} newlineOnly={} currentFormatGroupId=\"{}\"",
                            opId, compIdx, newlineOnly,
                            currentFormatGroup != null ? currentFormatGroup.getGroupId() : "none");

                    if (newlineOnly && currentFormatGroup != null) {
                        // A newline-only retain between two format retains means the format
                        // spans a paragraph break. Extend the current format group's span to
                        // include the newline, and set the bridge for reconnection on the next
                        // format retain.
                        RunPosition absPosResult = findRunPos(runs, localLogPos);
                        RunPosition nextAbsPosResult = findRunPos(runs, localLogPos + retainLen);
                        int absPos = absPosResult.absPos();
                        int absLength = nextAbsPosResult.absPos() - absPos;

                        log.debug("[RETAIN_PLAIN] opId={} compIdx={} — newline-only retain bridging formatGroup={} absPos={} absLength={}",
                                opId, compIdx, currentFormatGroup.getGroupId(), absPos, absLength);

                        int beforeSpanCount = currentFormatGroup.getSpans().size();
                        currentFormatGroup.setSpans(extendOrAddSpan(
                                currentFormatGroup.getSpans(), absPos, absLength));

                        log.debug("[RETAIN_PLAIN] formatGroup={} spanCount {} -> {} after newline bridge",
                                currentFormatGroup.getGroupId(), beforeSpanCount, currentFormatGroup.getSpans().size());

                        pendingFormatBridge = new HashMap<>();
                        pendingFormatBridge.put("actorEmail", authorEmail);
                        pendingFormatBridge.put("attributes", currentFormatGroup.getAttributes());
                        pendingFormatBridge.put("groupId", currentFormatGroup.getGroupId());
                        log.debug("[RETAIN_PLAIN] Set pendingFormatBridge to groupId={}", currentFormatGroup.getGroupId());
                    } else {
                        if (currentFormatGroup != null) {
                            log.debug("[RETAIN_PLAIN] opId={} compIdx={} — non-newline retain breaks formatGroup={}",
                                    opId, compIdx, currentFormatGroup.getGroupId());
                        }
                        currentFormatGroup = null;
                        pendingFormatBridge = null;
                    }

                    localLogPos += retainLen;

                    // ── Format retain (retain with attributes) ────────────────────
                    // Core of format suggestion building. The pending op is applying
                    // (or removing) formatting to a range of existing text.
                } else if (component.isRetain() && component.getAttributes() != null) {
                    currentInsertGroup = null;
                    currentDeleteGroup = null;

                    int retainLen = (int) component.getRetain();
                    Map<String, Object> componentAttrs = new LinkedHashMap<>(component.getAttributes());
                    String attrKeys = String.join(",", componentAttrs.keySet());

                    log.debug("\n[RETAIN_FORMAT] opId={} compIdx={} retain={} localLogPos={} attrKeys=\"{}\"",
                            opId, compIdx, retainLen, localLogPos, attrKeys);

                    RunPosition startPos = findRunPos(runs, localLogPos);
                    int runIdx = startPos.idx();
                    int startOffset = startPos.offset();

                    if (startOffset > 0 && runIdx < runs.size()) {
                        log.debug("[RETAIN_FORMAT] opId={} compIdx={} — splitting run at runIdx={} offset={} before processing",
                                opId, compIdx, runIdx, startOffset);
                        runIdx = splitAt(runs, runIdx, startOffset);
                    }

                    int remaining = retainLen;
                    int cursor = runIdx;

                    while (remaining > 0 && cursor < runs.size()) {
                        ReviewRun run = runs.get(cursor);

                        if (run.getDeleteSuggestion() != null) {
                            log.debug("[RETAIN_FORMAT] opId={} cursor={} — skipping deleted run text=\"{}\"",
                                    opId, cursor, run.getText());
                            cursor++;
                            continue;
                        }

                        if ("\n".equals(run.getText())) {
                            log.debug("[RETAIN_FORMAT] opId={} cursor={} — newline run, bridging format group if active",
                                    opId, cursor);
                            cursor++;
//                            remaining--;

                            if (currentFormatGroup != null) {
                                pendingFormatBridge = new HashMap<>();
                                pendingFormatBridge.put("actorEmail", authorEmail);
                                pendingFormatBridge.put("attributes", currentFormatGroup.getAttributes());
                                pendingFormatBridge.put("groupId", currentFormatGroup.getGroupId());
                                log.debug("[RETAIN_FORMAT] Set pendingFormatBridge to groupId={} after newline",
                                        currentFormatGroup.getGroupId());
                            }
                            continue;
                        }

                        if (run.getText().length() > remaining) {
                            log.debug("[RETAIN_FORMAT] opId={} cursor={} run.text.length={} > remaining={} — splitting",
                                    opId, cursor, run.getText().length(), remaining);
                            splitAt(runs, cursor, remaining);
                        }

                        ReviewRun target = runs.get(cursor);
                        int spanStart = target.getLogicalStart();
                        int spanLen = target.getText().length();

                        log.debug("[RETAIN_FORMAT] opId={} cursor={} processing run text=\"{}\" logicalStart={} length={} remaining={}",
                                opId, cursor, target.getText(), spanStart, spanLen, remaining);

                        Map<String, Object> rawIncomingAttrs = new LinkedHashMap<>(componentAttrs);

                        final int finalSpanStart = spanStart;
                        final int finalSpanLen = spanLen;
                        List<FormatSuggestionItem> coveringFormats = formatSuggestions.stream()
                                .filter(f -> f.getSpans().stream().anyMatch(s ->
                                        s.getStart() <= finalSpanStart
                                                && s.getStart() + s.getLength() >= finalSpanStart + finalSpanLen))
                                .toList();

                        log.debug("[RETAIN_FORMAT] opId={} cursor={} coveringFormatCount={}",
                                opId, cursor, coveringFormats.size());

                        for (FormatSuggestionItem fmt : new ArrayList<>(coveringFormats)) {
                            Map<String, Object> fmtAttrs = parseAttrs(fmt.getAttributes());
                            Map<String, Object> baseAttrs = new LinkedHashMap<>(
                                    target.getBaseAttributes() != null ? target.getBaseAttributes() : Collections.emptyMap()
                            );

                            List<FormatKeyDecision> decisions = classifyFormatKeyChanges(
                                    baseAttrs,
                                    fmtAttrs,
                                    rawIncomingAttrs
                            );

                            Map<String, Object> cancelKeys = new LinkedHashMap<>();
                            Map<String, Object> replacementKeys = new LinkedHashMap<>();

                            for (FormatKeyDecision d : decisions) {
                                switch (d.type()) {
                                    case CANCEL -> cancelKeys.put(d.key(), d.incomingValue());
                                    case REPLACE -> replacementKeys.put(d.key(), d.incomingValue());
                                }
                            }

                            Set<String> removeFromOldGroup = new LinkedHashSet<>();
                            removeFromOldGroup.addAll(cancelKeys.keySet());
                            removeFromOldGroup.addAll(replacementKeys.keySet());

                            if (!removeFromOldGroup.isEmpty()) {
                                int consumedBefore = retainLen - remaining - spanLen;

                                int finalCompIdx1 = compIdx;
                                Optional<PendingFormatCancellation> existingCancellation =
                                        pendingFormatCancellations.stream()
                                                .filter(c -> c.getGroupId().equals(fmt.getGroupId())
                                                        && c.getCancellingOpId().equals(opId)
                                                        && c.getRetainComponentIndex() == finalCompIdx1
                                                        && c.getConsumedBefore() + c.getLength() == consumedBefore)
                                                .findFirst();

                                if (existingCancellation.isPresent()) {
                                    existingCancellation.get().setLength(
                                            existingCancellation.get().getLength() + spanLen);
                                    log.debug("[RETAIN_FORMAT] opId={} — extending existing cancellation for formatGroup={} newLength={}",
                                            opId, fmt.getGroupId(), existingCancellation.get().getLength());
                                } else {
                                    pendingFormatCancellations.add(PendingFormatCancellation.builder()
                                            .groupId(fmt.getGroupId())
                                            .references(new ArrayList<>(fmt.getReferences()))
                                            .cancellingOpId(opId)
                                            .retainComponentIndex(compIdx)
                                            .consumedBefore(consumedBefore)
                                            .length(spanLen)
                                            .build());
                                    log.debug("[RETAIN_FORMAT] opId={} — new cancellation queued for formatGroup={} length={} consumedBefore={}",
                                            opId, fmt.getGroupId(), spanLen, consumedBefore);
                                }

                                removeRangeFromFormatSuggestion(fmt, spanStart, spanLen);

                                if (fmt.getSpans().isEmpty()) {
                                    formatSuggestions.remove(fmt);
                                    log.debug("[RETAIN_FORMAT] opId={} — formatGroup={} fully removed after cancel/replace",
                                            opId, fmt.getGroupId());
                                }

                                if (target.getSuggestionAttributes() != null) {
                                    for (String key : removeFromOldGroup) {
                                        target.getSuggestionAttributes().remove(key);
                                    }
                                }

                                for (String key : cancelKeys.keySet()) {
                                    rawIncomingAttrs.remove(key);
                                }
                            }
                        }

                        target.setSuggestionAttributes(
                                overlayAttrsPreserveNull(
                                        target.getSuggestionAttributes(),
                                        rawIncomingAttrs
                                )
                        );

                        String attrStr = attrsToJson(rawIncomingAttrs);
                        String suggestionAttrKeys = String.join(",", rawIncomingAttrs.keySet());

                        log.debug("[RETAIN_FORMAT] opId={} cursor={} suggestionAttrKeys=\"{}\" attrStr=\"{}\"",
                                opId, cursor, suggestionAttrKeys, attrStr);

                        if (!rawIncomingAttrs.isEmpty()) {
                            if (currentFormatGroup == null) {
                                final String finalAttrStr = attrStr;
                                final String finalActorEmail = authorEmail;
                                final int finalSpanStart2 = spanStart;
                                final int finalSpanEnd2 = spanStart + spanLen;
                                log.info("finalSpanStart2: " + finalSpanStart2);
                                log.info("finalSpanEnd2: " + finalSpanEnd2);

                                FormatSuggestionItem prevAdj = formatSuggestions.stream()
                                        .filter(f -> f.getActorEmail().equals(finalActorEmail))
                                        .filter(f -> f.getAttributes().equals(finalAttrStr))
                                        .filter(f -> f.getSpans().stream().anyMatch(s -> s.getStart() + s.getLength() == finalSpanStart2))
                                        .findFirst()
                                        .orElse(null);

                                FormatSuggestionItem nextAdj = formatSuggestions.stream()
                                        .filter(f -> f.getActorEmail().equals(finalActorEmail))
                                        .filter(f -> f.getAttributes().equals(finalAttrStr))
                                        .filter(f -> f.getSpans().stream().anyMatch(s -> s.getStart() == finalSpanEnd2))
                                        .findFirst()
                                        .orElse(null);

                                FormatSuggestionItem existing = null;
                                if (prevAdj != null) {
                                    log.info(prevAdj.toString());
                                } else {
                                    log.info("No prevAdj");
                                }
                                if (nextAdj != null) {
                                    log.info(nextAdj.toString());
                                } else {
                                    log.info("No prevAdj");
                                }

                                if (prevAdj != null) {
                                    existing = prevAdj;
                                    log.debug("[RETAIN_FORMAT] opId={} cursor={} — JOINED PREV formatGroup={}",
                                            opId, cursor, existing.getGroupId());

                                    if (nextAdj != null && !nextAdj.getGroupId().equals(prevAdj.getGroupId())) {
                                        log.debug("[RETAIN_FORMAT] opId={} cursor={} — UNIFYING next formatGroup={} into prev formatGroup={}",
                                                opId, cursor, nextAdj.getGroupId(), prevAdj.getGroupId());

                                        for (OpReference ref : nextAdj.getReferences()) {
                                            boolean alreadyExists = existing.getReferences().stream()
                                                    .anyMatch(r -> r.opId().equals(ref.opId()) && Objects.equals(r.componentIndex(), ref.componentIndex()));
                                            if (!alreadyExists) {
                                                existing.getReferences().add(ref);
                                            }
                                        }

                                        List<FormatSuggestionSpan> mergedSpans = new ArrayList<>();
                                        mergedSpans.addAll(existing.getSpans().stream()
                                                .map(s -> FormatSuggestionSpan.builder()
                                                        .start(s.getStart()).length(s.getLength()).build())
                                                .toList());
                                        mergedSpans.addAll(nextAdj.getSpans().stream()
                                                .map(s -> FormatSuggestionSpan.builder()
                                                        .start(s.getStart()).length(s.getLength()).build())
                                                .toList());
                                        existing.setSpans(mergeAdjacentSpans(mergedSpans));

                                        for (String dep : nextAdj.getDependsOnInsertGroupIds()) {
                                            if (!existing.getDependsOnInsertGroupIds().contains(dep)) {
                                                existing.getDependsOnInsertGroupIds().add(dep);
                                            }
                                        }

                                        if (nextAdj.getCreatedAt().compareTo(existing.getCreatedAt()) > 0) {
                                            existing.setCreatedAt(nextAdj.getCreatedAt());
                                        }

                                        formatSuggestions.removeIf(f -> f.getGroupId().equals(nextAdj.getGroupId()));

                                        log.debug("[RETAIN_FORMAT] opId={} cursor={} — next formatGroup={} unified into {}",
                                                opId, cursor, nextAdj.getGroupId(), existing.getGroupId());
                                    }

                                } else if (nextAdj != null) {
                                    existing = nextAdj;
                                    log.debug("[RETAIN_FORMAT] opId={} cursor={} — JOINED NEXT formatGroup={}",
                                            opId, cursor, existing.getGroupId());

                                } else if (pendingFormatBridge != null
                                        && authorEmail.equals(pendingFormatBridge.get("actorEmail"))
                                        && attrStr.equals(pendingFormatBridge.get("attributes"))) {
                                    final String bridgeGroupId = pendingFormatBridge.get("groupId");
                                    existing = formatSuggestions.stream()
                                            .filter(f -> f.getGroupId().equals(bridgeGroupId))
                                            .findFirst()
                                            .orElse(null);

                                    if (existing != null) {
                                        log.debug("[RETAIN_FORMAT] opId={} cursor={} — BRIDGE reconnected to formatGroup={} across newline",
                                                opId, cursor, existing.getGroupId());
                                    }
                                }

                                if (existing == null) {
                                    existing = FormatSuggestionItem.builder()
                                            .groupId(nextId())
                                            .actorEmail(authorEmail)
                                            .createdAt(createdAt)
                                            .attributes(attrStr)
                                            .references(new ArrayList<>(Collections.singletonList(
                                                    new OpReference(opId, compIdx))))
                                            .spans(new ArrayList<>())
                                            .previewText("")
                                            .dependsOnInsertGroupIds(new ArrayList<>())
                                            .build();
                                    formatSuggestions.add(existing);
                                    log.debug("[RETAIN_FORMAT] opId={} cursor={} — CREATED new formatGroup={} attrKeys=\"{}\"",
                                            opId, cursor, existing.getGroupId(), suggestionAttrKeys);
                                }

                                currentFormatGroup = existing;
                            }

                            if (target.getInsertSuggestion() != null
                                    && !currentFormatGroup.getDependsOnInsertGroupIds()
                                    .contains(target.getInsertSuggestion().getGroupId())) {
                                currentFormatGroup.getDependsOnInsertGroupIds()
                                        .add(target.getInsertSuggestion().getGroupId());
                                log.debug("[RETAIN_FORMAT] opId={} — formatGroup={} now depends on insertGroup={}",
                                        opId, currentFormatGroup.getGroupId(),
                                        target.getInsertSuggestion().getGroupId());
                            }

                            final int finalCompIdx = compIdx;
                            boolean refExists = currentFormatGroup.getReferences().stream()
                                    .anyMatch(r -> r.opId().equals(opId) && r.componentIndex() == finalCompIdx);
                            if (!refExists) {
                                currentFormatGroup.getReferences().add(
                                        new OpReference(opId, compIdx));
                            }

                            int adjacentIdx = findAdjacentSpanIndex(currentFormatGroup.getSpans(), spanStart);
                            if (adjacentIdx != -1) {
                                currentFormatGroup.getSpans().get(adjacentIdx)
                                        .setLength(currentFormatGroup.getSpans().get(adjacentIdx).getLength() + spanLen);
                                currentFormatGroup.setSpans(mergeAdjacentSpans(
                                        currentFormatGroup.getSpans().stream()
                                                .map(s -> FormatSuggestionSpan.builder()
                                                        .start(s.getStart()).length(s.getLength()).build())
                                                .collect(Collectors.toList())));
                                log.debug("[RETAIN_FORMAT] opId={} cursor={} — extended adjacent span of formatGroup={}",
                                        opId, cursor, currentFormatGroup.getGroupId());
                            } else {
                                currentFormatGroup.getSpans().add(
                                        FormatSuggestionSpan.builder().start(spanStart).length(spanLen).build());
                                currentFormatGroup.setSpans(mergeAdjacentSpans(
                                        currentFormatGroup.getSpans().stream()
                                                .map(s -> FormatSuggestionSpan.builder()
                                                        .start(s.getStart()).length(s.getLength()).build())
                                                .collect(Collectors.toList())));
                                log.debug("[RETAIN_FORMAT] opId={} cursor={} — added new span to formatGroup={} start={} length={}",
                                        opId, cursor, currentFormatGroup.getGroupId(), spanStart, spanLen);
                            }

                            pendingFormatBridge = new HashMap<>();
                            pendingFormatBridge.put("actorEmail", authorEmail);
                            pendingFormatBridge.put("attributes", attrStr);
                            pendingFormatBridge.put("groupId", currentFormatGroup.getGroupId());

                        } else {
                            log.debug("[RETAIN_FORMAT] opId={} cursor={} — no suggestion attrs after stripping nulls",
                                    opId, cursor);
                        }

                        log.info(currentFormatGroup.toString());

                        remaining -= spanLen;
                        cursor++;
                    }

                    localLogPos += retainLen;
                } else if (component.isInsert() && component.getInsert() instanceof String insertText) {
                    currentDeleteGroup = null;
                    currentFormatGroup = null;

                    Map<String, Object> rawAttrs = component.getAttributes() != null
                            ? new LinkedHashMap<>(component.getAttributes())
                            : new LinkedHashMap<>();
                    String rawAttrKeys = String.join(",", rawAttrs.keySet());

                    log.debug("\n[INSERT] opId={} compIdx={} text=\"{}\" localLogPos={} attrKeys=\"{}\"",
                            opId, compIdx, insertText, localLogPos, rawAttrKeys);

                    RunPosition insertPos = findRunPos(runs, localLogPos);
                    int runIndex = insertPos.idx();
                    int insertOffset = insertPos.offset();
                    int insertAbsPos = insertPos.absPos();

                    int insertAtIdx = runIndex;
                    if (insertOffset > 0 && runIndex < runs.size()) {
                        log.debug("[INSERT] opId={} compIdx={} — splitting run at runIndex={} offset={} before inserting",
                                opId, compIdx, runIndex, insertOffset);
                        insertAtIdx = splitAt(runs, runIndex, insertOffset);
                    }

                    ReviewRun prevRun = (insertAtIdx > 0) ? runs.get(insertAtIdx - 1) : null;
                    ReviewRun nextRun = (insertAtIdx < runs.size()) ? runs.get(insertAtIdx) : null;

                    log.debug("[INSERT] opId={} compIdx={} insertAtIdx={} insertAbsPos={}", opId, compIdx, insertAtIdx, insertAbsPos);
                    log.debug("[INSERT] opId={} compIdx={} prevRun text=\"{}\" prevInsertGroupId=\"{}\" prevActor=\"{}\"",
                            opId, compIdx,
                            prevRun != null ? prevRun.getText() : "NONE",
                            prevRun != null && prevRun.getInsertSuggestion() != null ? prevRun.getInsertSuggestion().getGroupId() : "none",
                            prevRun != null && prevRun.getInsertSuggestion() != null ? prevRun.getInsertSuggestion().getActorEmail() : "none");
                    log.debug("[INSERT] opId={} compIdx={} nextRun text=\"{}\" nextInsertGroupId=\"{}\" nextActor=\"{}\"",
                            opId, compIdx,
                            nextRun != null ? nextRun.getText() : "NONE",
                            nextRun != null && nextRun.getInsertSuggestion() != null ? nextRun.getInsertSuggestion().getGroupId() : "none",
                            nextRun != null && nextRun.getInsertSuggestion() != null ? nextRun.getInsertSuggestion().getActorEmail() : "none");

                    // ── Determine insert suggestion group ────────────────────
                    // Reuse an adjacent same-actor group (continuation of previous insert)
                    // or create a new group.
                    if (currentInsertGroup == null) {
                        InsertSuggestion prevAdj = (prevRun != null
                                && prevRun.getInsertSuggestion() != null
                                && authorEmail.equals(prevRun.getInsertSuggestion().getActorEmail()))
                                ? prevRun.getInsertSuggestion() : null;

                        InsertSuggestion nextAdj = (nextRun != null
                                && nextRun.getInsertSuggestion() != null
                                && authorEmail.equals(nextRun.getInsertSuggestion().getActorEmail()))
                                ? nextRun.getInsertSuggestion() : null;

                        InsertSuggestion adj = (prevAdj != null) ? prevAdj : nextAdj;

                        if (adj != null) {
                            currentInsertGroup = adj;
                            final int finalCompIdx2 = compIdx;
                            boolean refExists = currentInsertGroup.getReferences().stream()
                                    .anyMatch(r -> r.opId().equals(opId) && r.componentIndex() == finalCompIdx2);
                            if (!refExists) {
                                currentInsertGroup.getReferences().add(
                                        new OpReference(opId, compIdx));
                            }
                            log.debug("[INSERT] opId={} compIdx={} — JOINED adjacent insertGroup={} (same actor)",
                                    opId, compIdx, currentInsertGroup.getGroupId());
                        } else {
                            currentInsertGroup = InsertSuggestion.builder()
                                    .groupId(nextId())
                                    .actorEmail(authorEmail)
                                    .createdAt(createdAt)
                                    .references(new ArrayList<>(Collections.singletonList(
                                            new OpReference(opId, compIdx))))
                                    .startIndex(localLogPos)
                                    .build();
                            log.debug("[INSERT] opId={} compIdx={} — CREATED new insertGroup={} for actor={}",
                                    opId, compIdx, currentInsertGroup.getGroupId(), authorEmail);
                        }
                    } else if (createdAt.compareTo(currentInsertGroup.getCreatedAt()) > 0) {
                        currentInsertGroup.setCreatedAt(createdAt);
                        log.debug("[INSERT] opId={} compIdx={} — updated insertGroup={} createdAt={}",
                                opId, compIdx, currentInsertGroup.getGroupId(), createdAt);
                    }

                    Map<String, Object> ownAttrs = new LinkedHashMap<>(rawAttrs);
                    Set<String> extendedGroupIds = new LinkedHashSet<>();

                    // ── Extend any already-existing adjacent format suggestion ────
                    // For each attribute in ownAttrs, check whether a format suggestion
                    // from a different actor ends exactly at localLogPos with that attr.
                    // If so, extend it to include the inserted text.
                    for (String key : new ArrayList<>(ownAttrs.keySet())) {
                        Object value = ownAttrs.get(key);
                        Map<String, Object> singleAttrMap = new LinkedHashMap<>();
                        singleAttrMap.put(key, value);
                        String singleAttrStr = attrsToJson(singleAttrMap);

                        FormatSuggestionItem existingAdj = findAdjacentFormatGroupByBoundary(
                                formatSuggestions, singleAttrStr, localLogPos);

                        if (existingAdj == null) continue;

                        extendFormatGroupAtBoundary(existingAdj, localLogPos, insertText.length(),
                                opId, compIdx, currentInsertGroup.getGroupId());
                        extendedGroupIds.add(existingAdj.getGroupId());
                        ownAttrs.remove(key);

                        log.debug("[INSERT] opId={} compIdx={} — EXTENDED existing adjacent formatGroup={} for key=\"{}\" at boundary={}; remaining ownAttrKeys=\"{}\"",
                                opId, compIdx, existingAdj.getGroupId(), key, localLogPos,
                                String.join(",", ownAttrs.keySet()));
                    }

                    // ── Check prev neighbor for inherited attrs (different actor) ──
                    // If actor A inserted bold text and actor B inserts text immediately
                    // after with the same bold, the bold is "inherited" from A.
                    // We create a format suggestion owned by A spanning both A's and B's text.
                    Map<String, Object> prevEffectiveAttrs = getEffectiveAttrs(prevRun);
                    Map<String, Object> nextEffectiveAttrs = getEffectiveAttrs(nextRun);

                    if (!ownAttrs.isEmpty()
                            && prevRun != null
                            && prevRun.getInsertSuggestion() != null
                            && !authorEmail.equals(prevRun.getInsertSuggestion().getActorEmail())
                            && !prevEffectiveAttrs.isEmpty()) {

                        Map<String, Object> inherited = intersectAttrs(ownAttrs, prevEffectiveAttrs);
                        String inheritedKeys = String.join(",", inherited.keySet());
                        log.debug("[INSERT] opId={} compIdx={} — checking PREV neighbor for inherited attrs from actor={} inheritedKeys=\"{}\"",
                                opId, compIdx, prevRun.getInsertSuggestion().getActorEmail(), inheritedKeys);

                        if (!inherited.isEmpty()) {
                            String attrStr = attrsToJson(inherited);
                            InsertGroupCollection prevGroup = collectInsertGroupRunsWithAttrs(
                                    runs, prevRun.getInsertSuggestion().getGroupId(), inherited);

                            if (prevGroup != null) {
                                int spanStart = prevGroup.start;
                                int spanEnd = localLogPos + insertText.length();

                                log.debug("[INSERT] opId={} compIdx={} — creating/extending inherited-attr format suggestion from prevGroup owner={} spanStart={} spanEnd={}",
                                        opId, compIdx, prevRun.getInsertSuggestion().getActorEmail(), spanStart, spanEnd);

                                // Fallback: find or create by spanStart
                                String ownerEmail = prevRun.getInsertSuggestion().getActorEmail();

                                FormatSuggestionItem g = FormatSuggestionItem.builder()
                                        .groupId(nextId())
                                        .actorEmail(ownerEmail)
                                        .createdAt(prevRun.getInsertSuggestion().getCreatedAt())
                                        .attributes(attrStr)
                                        .references(new ArrayList<>(prevRun.getInsertSuggestion().getReferences()))
                                        .spans(new ArrayList<>(Collections.singletonList(
                                                FormatSuggestionSpan.builder().start(spanStart).length(spanEnd - spanStart).build())))
                                        .previewText("")
                                        .dependsOnInsertGroupIds(new ArrayList<>(Arrays.asList(
                                                prevRun.getInsertSuggestion().getGroupId(),
                                                currentInsertGroup.getGroupId())))
                                        .build();
                                g.getReferences().add(new OpReference(opId, compIdx));
                                formatSuggestions.add(g);
                                extendedGroupIds.add(g.getGroupId());
                                log.debug("[INSERT] opId={} compIdx={} — CREATED fallback inherited-attr formatGroup={} from prev neighbor",
                                        opId, compIdx, g.getGroupId());

                                stripAttrsFromRuns(runs, prevGroup.indices, inherited);
                                ownAttrs = subtractAttrs(ownAttrs, inherited);
                                log.debug("[INSERT] opId={} compIdx={} — stripped inherited attrs from prev runs, remaining ownAttrKeys=\"{}\"",
                                        opId, compIdx, String.join(",", ownAttrs.keySet()));
                            }
                        }
                    }

                    // ── Check next neighbor for inherited attrs (different actor) ──
                    // Mirror of prev-neighbor check — handles B inserting text BEFORE A's bold insert.
                    if (!ownAttrs.isEmpty()
                            && nextRun != null
                            && nextRun.getInsertSuggestion() != null
                            && !authorEmail.equals(nextRun.getInsertSuggestion().getActorEmail())
                            && !nextEffectiveAttrs.isEmpty()) {

                        Map<String, Object> inherited = intersectAttrs(ownAttrs, nextEffectiveAttrs);
                        String inheritedKeys = String.join(",", inherited.keySet());
                        log.debug("[INSERT] opId={} compIdx={} — checking NEXT neighbor for inherited attrs from actor={} inheritedKeys=\"{}\"",
                                opId, compIdx, nextRun.getInsertSuggestion().getActorEmail(), inheritedKeys);

                        if (!inherited.isEmpty()) {
                            String ownerEmail = nextRun.getInsertSuggestion().getActorEmail();
                            String attrStr = attrsToJson(inherited);
                            InsertGroupCollection nextGroup = collectInsertGroupRunsWithAttrs(
                                    runs, nextRun.getInsertSuggestion().getGroupId(), inherited);

                            if (nextGroup != null) {
                                int spanStart = localLogPos;
                                int spanEnd = nextGroup.end;

                                log.debug("[INSERT] opId={} compIdx={} — creating/extending inherited-attr format suggestion from nextGroup owner={} spanStart={} spanEnd={}",
                                        opId, compIdx, ownerEmail, spanStart, spanEnd);

                                FormatSuggestionItem g = FormatSuggestionItem.builder()
                                        .groupId(nextId())
                                        .actorEmail(ownerEmail)
                                        .createdAt(nextRun.getInsertSuggestion().getCreatedAt())
                                        .attributes(attrStr)
                                        .references(new ArrayList<>(nextRun.getInsertSuggestion().getReferences()))
                                        .spans(new ArrayList<>(Collections.singletonList(
                                                FormatSuggestionSpan.builder().start(spanStart).length(spanEnd - spanStart).build())))
                                        .previewText("")
                                        .dependsOnInsertGroupIds(new ArrayList<>(Arrays.asList(
                                                nextRun.getInsertSuggestion().getGroupId(),
                                                currentInsertGroup.getGroupId())))
                                        .build();
                                g.getReferences().add(new OpReference(opId, compIdx));
                                formatSuggestions.add(g);
                                extendedGroupIds.add(g.getGroupId());
                                log.debug("[INSERT] opId={} compIdx={} — CREATED inherited-attr formatGroup={} from next neighbor attrKeys=\"{}\"",
                                        opId, compIdx, g.getGroupId(), inheritedKeys);

                                stripAttrsFromRuns(runs, nextGroup.indices, inherited);
                                ownAttrs = subtractAttrs(ownAttrs, inherited);
                                log.debug("[INSERT] opId={} compIdx={} — stripped inherited attrs from next runs, remaining ownAttrKeys=\"{}\"",
                                        opId, compIdx, String.join(",", ownAttrs.keySet()));
                            }
                        }
                    }

                    // ── Splice new runs into the runs list ───────────────────────
                    // Split insertText on "\n" so each newline becomes its own run.
                    String[] parts = insertText.split("\n", -1);
                    int spliceAt = insertAtIdx;
                    int runPos = insertAbsPos;

                    log.debug("[INSERT] opId={} compIdx={} — splicing {} part(s) at insertAtIdx={} insertAbsPos={} insertGroup={}",
                            opId, compIdx, parts.length, insertAtIdx, insertAbsPos, currentInsertGroup.getGroupId());

                    for (int i = 0; i < parts.length; i++) {
                        if (!parts[i].isEmpty()) {
                            ReviewRun prevInsertedRun = (spliceAt > 0) ? runs.get(spliceAt - 1) : null;

                            boolean canMergeIntoPrev =
                                    prevInsertedRun != null
                                            && prevInsertedRun.getDeleteSuggestion() == null
                                            && prevInsertedRun.getInsertSuggestion() != null
                                            && currentInsertGroup != null
                                            && prevInsertedRun.getInsertSuggestion().getGroupId()
                                            .equals(currentInsertGroup.getGroupId())
                                            && !"\n".equals(prevInsertedRun.getText())
                                            && !"\n".equals(parts[i])
                                            && attrsEq(
                                            prevInsertedRun.getBaseAttributes() != null
                                                    ? prevInsertedRun.getBaseAttributes()
                                                    : Collections.emptyMap(),
                                            ownAttrs
                                    )
                                            && (prevInsertedRun.getSuggestionAttributes() == null
                                            || prevInsertedRun.getSuggestionAttributes().isEmpty())
                                            && prevInsertedRun.getLogicalStart() + prevInsertedRun.getText().length() == runPos;

                            if (canMergeIntoPrev) {
                                prevInsertedRun.setText(prevInsertedRun.getText() + parts[i]);

                                // Keep the insert suggestion metadata fresh/merged
                                InsertSuggestion mergedInsertSuggestion = prevInsertedRun.getInsertSuggestion();
                                final int finalCompIdx = compIdx;
                                boolean refExists = mergedInsertSuggestion.getReferences().stream()
                                        .anyMatch(r -> r.opId().equals(opId) && r.componentIndex() == finalCompIdx);
                                if (!refExists) {
                                    mergedInsertSuggestion.getReferences().add(new OpReference(opId, compIdx));
                                }
                                if (createdAt.compareTo(mergedInsertSuggestion.getCreatedAt()) > 0) {
                                    mergedInsertSuggestion.setCreatedAt(createdAt);
                                }

                                prevInsertedRun.setInsertSuggestion(copyInsertSuggestion(mergedInsertSuggestion));

                                log.debug("[INSERT] opId={} compIdx={} — MERGED text=\"{}\" into previous run at logicalStart={} group={}",
                                        opId, compIdx, parts[i], prevInsertedRun.getLogicalStart(), currentInsertGroup.getGroupId());
                            } else {
                                ReviewRun newRun = ReviewRun.builder()
                                        .text(parts[i])
                                        .baseAttributes(new LinkedHashMap<>(ownAttrs))
                                        .suggestionAttributes(new LinkedHashMap<>())
                                        .logicalStart(runPos)
                                        .opId(opId)
                                        .insertComponentIndex(compIdx)
                                        .insertSuggestion(copyInsertSuggestion(currentInsertGroup))
                                        .build();
                                runs.add(spliceAt++, newRun);
                                log.debug("[INSERT] opId={} compIdx={} — inserted run text=\"{}\" at logicalStart={} group={}",
                                        opId, compIdx, parts[i], runPos, currentInsertGroup.getGroupId());
                            }

                            runPos += parts[i].length();
                        }
                        if (i < parts.length - 1) {
                            ReviewRun newlineRun = ReviewRun.builder()
                                    .text("\n")
                                    .baseAttributes(new LinkedHashMap<>())
                                    .suggestionAttributes(new LinkedHashMap<>())
                                    .logicalStart(runPos)
                                    .opId(opId)
                                    .insertComponentIndex(compIdx)
                                    .insertSuggestion(copyInsertSuggestion(currentInsertGroup))
                                    .build();
                            runs.add(spliceAt++, newlineRun);
                            log.debug("[INSERT] opId={} compIdx={} — inserted NEWLINE run at logicalStart={} group={}",
                                    opId, compIdx, runPos, currentInsertGroup.getGroupId());
                            runPos += 1;
                        }
                    }

                    // Shift all subsequent runs right to fill the inserted space
                    int shiftLen = insertText.length();
                    int shiftedCount = 0;
                    for (int i = spliceAt; i < runs.size(); i++) {
                        runs.get(i).setLogicalStart(runs.get(i).getLogicalStart() + shiftLen);
                        shiftedCount++;
                    }
                    log.debug("[INSERT] opId={} compIdx={} — shifted {} subsequent run(s) right by {}",
                            opId, compIdx, shiftedCount, shiftLen);

                    // Shift format spans to account for the inserted text
                    log.debug("[INSERT] opId={} compIdx={} — shifting format spans: insertAbsPos={} shiftLen={} extendedGroupCount={}",
                            opId, compIdx, insertAbsPos, shiftLen, extendedGroupIds.size());
                    shiftFormatSpansForInsert(formatSuggestions, insertAbsPos, insertText.length(), extendedGroupIds);

                    localLogPos += insertText.length();
                    log.debug("[INSERT] opId={} compIdx={} — done. new localLogPos={}", opId, compIdx, localLogPos);

                    // ── Delete ────────────────────────────────────────────────────
                    // Text is being deleted. Rather than removing runs, we mark them
                    // with a deleteSuggestion so they remain visible in review mode.
                } else if (component.isDelete()) {
                    currentInsertGroup = null;
                    currentFormatGroup = null;

                    int deleteLen = component.getDelete();
                    log.debug("\n[DELETE] opId={} compIdx={} deleteLength={} localLogPos={}", opId, compIdx, deleteLen, localLogPos);

                    RunPosition deletePos = findRunPos(runs, localLogPos);
                    int ri = deletePos.idx();
                    int deleteOffset = deletePos.offset();
                    int cursor = ri;

                    if (deleteOffset > 0 && ri < runs.size()) {
                        log.debug("[DELETE] opId={} compIdx={} — splitting run at ri={} offset={} before deleting",
                                opId, compIdx, ri, deleteOffset);
                        cursor = splitAt(runs, ri, deleteOffset);
                    }

                    if (currentDeleteGroup == null) {
                        ReviewRun prevRunD = (cursor > 0) ? runs.get(cursor - 1) : null;
                        ReviewRun nextRunD = (cursor + 1 < runs.size()) ? runs.get(cursor + 1) : null;

                        DeleteSuggestion prevAdj = (prevRunD != null
                                && prevRunD.getDeleteSuggestion() != null
                                && authorEmail.equals(prevRunD.getDeleteSuggestion().getActorEmail()))
                                ? prevRunD.getDeleteSuggestion()
                                : null;

                        DeleteSuggestion nextAdj = (nextRunD != null
                                && nextRunD.getDeleteSuggestion() != null
                                && authorEmail.equals(nextRunD.getDeleteSuggestion().getActorEmail()))
                                ? nextRunD.getDeleteSuggestion()
                                : null;

                        if (prevAdj != null) {
                            currentDeleteGroup = prevAdj;

                            final int fc5 = compIdx;
                            boolean refExists = currentDeleteGroup.getReferences().stream()
                                    .anyMatch(r -> r.opId().equals(opId) && r.componentIndex() == fc5);
                            if (!refExists) {
                                currentDeleteGroup.getReferences().add(
                                        new OpReference(opId, compIdx));
                            }

                            log.debug("[DELETE] opId={} compIdx={} — JOINED PREV deleteGroup={}",
                                    opId, compIdx, currentDeleteGroup.getGroupId());

                            if (nextAdj != null && !nextAdj.getGroupId().equals(prevAdj.getGroupId())) {
                                log.debug("[DELETE] opId={} compIdx={} — UNIFYING next deleteGroup={} into prev deleteGroup={}",
                                        opId, compIdx, nextAdj.getGroupId(), prevAdj.getGroupId());

                                for (OpReference ref : nextAdj.getReferences()) {
                                    boolean alreadyExists = currentDeleteGroup.getReferences().stream()
                                            .anyMatch(r -> r.opId().equals(ref.opId()) && r.componentIndex() == ref.componentIndex());
                                    if (!alreadyExists) {
                                        currentDeleteGroup.getReferences().add(ref);
                                    }
                                }

                                if (nextAdj.getCreatedAt().compareTo(currentDeleteGroup.getCreatedAt()) > 0) {
                                    currentDeleteGroup.setCreatedAt(nextAdj.getCreatedAt());
                                }

                                for (ReviewRun existingRun : runs) {
                                    if (existingRun.getDeleteSuggestion() != null
                                            && nextAdj.getGroupId().equals(existingRun.getDeleteSuggestion().getGroupId())) {
                                        existingRun.setDeleteSuggestion(copyDeleteSuggestion(currentDeleteGroup));
                                    }
                                }

                                log.debug("[DELETE] opId={} compIdx={} — next deleteGroup={} unified into {}",
                                        opId, compIdx, nextAdj.getGroupId(), currentDeleteGroup.getGroupId());
                            }

                        } else if (nextAdj != null) {
                            currentDeleteGroup = nextAdj;

                            final int fc5 = compIdx;
                            boolean refExists = currentDeleteGroup.getReferences().stream()
                                    .anyMatch(r -> r.opId().equals(opId) && r.componentIndex() == fc5);
                            if (!refExists) {
                                currentDeleteGroup.getReferences().add(
                                        new OpReference(opId, compIdx));
                            }

                            log.debug("[DELETE] opId={} compIdx={} — JOINED NEXT deleteGroup={}",
                                    opId, compIdx, currentDeleteGroup.getGroupId());

                        } else {
                            currentDeleteGroup = DeleteSuggestion.builder()
                                    .groupId(nextId())
                                    .actorEmail(authorEmail)
                                    .createdAt(createdAt)
                                    .references(new ArrayList<>(Collections.singletonList(
                                            new OpReference(opId, compIdx))))
                                    .build();

                            log.debug("[DELETE] opId={} compIdx={} — CREATED new deleteGroup={} for actor={}",
                                    opId, compIdx, currentDeleteGroup.getGroupId(), authorEmail);
                        }
                    }

                    int remaining = deleteLen;

                    while (remaining > 0 && cursor < runs.size()) {
                        ReviewRun run = runs.get(cursor);

                        if (run.getDeleteSuggestion() != null) {
                            log.debug("[DELETE] opId={} cursor={} — skipping already-deleted run text=\"{}\"",
                                    opId, cursor, run.getText());
                            cursor++;
                            continue;
                        }

                        if ("\n".equals(run.getText())) {
                            run.setDeleteSuggestion(copyDeleteSuggestion(currentDeleteGroup));
                            log.debug("[DELETE] opId={} cursor={} — marked NEWLINE run as DELETE suggestion group={}",
                                    opId, cursor, currentDeleteGroup.getGroupId());
                            remaining--;
                            localLogPos++;
                            cursor++;
                            continue;
                        }

                        if (remaining < run.getText().length()) {
                            log.debug("[DELETE] opId={} cursor={} — remaining={} < run.text.length={}, splitting",
                                    opId, cursor, remaining, run.getText().length());
                            splitAt(runs, cursor, remaining);
                        }

                        ReviewRun target = runs.get(cursor);
                        int len = target.getText().length();

                        if (target.getInsertSuggestion() != null) {
                            log.debug("[DELETE] opId={} cursor={} — run text=\"{}\" is INSERT SUGGESTION (insertGroup={}), cancelling via API",
                                    opId, cursor, target.getText(), target.getInsertSuggestion().getGroupId());

                            int shiftLen = target.getText().length();
                            runs.remove(cursor);
                            for (int i = cursor; i < runs.size(); i++) {
                                runs.get(i).setLogicalStart(runs.get(i).getLogicalStart() - shiftLen);
                            }

                            cancelInsert(
                                    actorEmail,
                                    noteId,
                                    new CancelInsertPayload(
                                            target.getOpId(),
                                            opId,
                                            target.getInsertComponentIndex(),
                                            len,
                                            compIdx
                                    )
                            );

                            log.debug("[DELETE] opId={} cursor={} — API call sent for insert cancellation: insertOpId={} overlapLength={}",
                                    opId, cursor, target.getOpId(), len);

                            remaining -= len;
//                            localLogPos += len;
                            continue;
                        }

                        target.setDeleteSuggestion(copyDeleteSuggestion(currentDeleteGroup));
                        log.debug("[DELETE] opId={} cursor={} — marked run text=\"{}\" as DELETE suggestion group={}",
                                opId, cursor, target.getText(), currentDeleteGroup.getGroupId());
                        remaining -= len;
                        localLogPos += len;
                        cursor++;
                    }

                    log.debug("[DELETE] opId={} compIdx={} — done. localLogPos={}", opId, compIdx, localLogPos);
                }
            }

            log.info("[REVIEW_BUILD] Finished processing opId={} — total runs={} formatSuggestions={}",
                    opId, runs.size(), formatSuggestions.size());
        }

        // ── STEP 3: Build preview texts ───────────────────────────────────────
        // Compute a short text preview for each format suggestion so the sidebar
        // can display it without parsing the full delta.
        log.info("\n[REVIEW_BUILD] Building preview texts for {} format suggestion(s)", formatSuggestions.size());

        for (FormatSuggestionItem fmt : formatSuggestions) {
            if (fmt.getPreviewText() != null && !fmt.getPreviewText().isEmpty()) {
                log.debug("[PREVIEW_TEXT] groupId={} — already has previewText, skipping", fmt.getGroupId());
                continue;
            }

            StringBuilder texts = new StringBuilder();
            List<FormatSuggestionSpan> orderedSpans = fmt.getSpans().stream()
                    .sorted(Comparator.comparingInt(FormatSuggestionSpan::getStart))
                    .toList();
            Integer prevSpanEnd = null;

            for (FormatSuggestionSpan span : orderedSpans) {
                int spanStart = span.getStart();
                int spanEnd = span.getStart() + span.getLength();

                if (prevSpanEnd != null && spanStart > prevSpanEnd) {
                    boolean sawNewlineGap = false;
                    for (ReviewRun run : runs) {
                        if (run.getDeleteSuggestion() != null) continue;
                        int runStart = run.getLogicalStart();
                        int runEnd = run.getLogicalStart() + run.getText().length();
                        if (runEnd > prevSpanEnd && runStart < spanStart && "\n".equals(run.getText())) {
                            sawNewlineGap = true;
                            break;
                        }
                    }
                    texts.append(sawNewlineGap ? " ↵ " : " ... ");
                    log.debug("[PREVIEW_TEXT] groupId={} — gap between spans, sawNewlineGap={}", fmt.getGroupId(), sawNewlineGap);
                }

                for (ReviewRun run : runs) {
                    if (run.getDeleteSuggestion() != null) continue;
                    int runStart = run.getLogicalStart();
                    int runEnd = run.getLogicalStart() + run.getText().length();
                    if (runEnd > spanStart && runStart < spanEnd) {
                        texts.append("\n".equals(run.getText()) ? " ↵ " : run.getText());
                    }
                }

                prevSpanEnd = spanEnd;
            }

            String preview = texts.toString();
            if (preview.length() > 60) preview = preview.substring(0, 60);
            fmt.setPreviewText(preview);
            log.debug("[PREVIEW_TEXT] groupId={} previewText=\"{}\"", fmt.getGroupId(), preview);
        }

        // ── STEP 4: Flush pending format cancellations ────────────────────────
        // Now that all run mutations are complete, make the backend split calls
        // for format cancellations.
        log.info("\n[REVIEW_BUILD] Flushing {} pending format cancellation(s) to backend",
                pendingFormatCancellations.size());

        for (PendingFormatCancellation c : pendingFormatCancellations) {
            log.debug("[CANCEL_FORMAT_API] groupId={} cancellingOpId={} retainComponentIndex={} length={} consumedBefore={}",
                    c.getGroupId(), c.getCancellingOpId(), c.getRetainComponentIndex(),
                    c.getLength(), c.getConsumedBefore());

            cancelFormat(
                    actorEmail,
                    noteId,
                    new CancelFormatPayload(
                        c.getReferences(),
                        c.getCancellingOpId(),
                        c.getRetainComponentIndex(),
                        c.getLength(),
                        c.getConsumedBefore()));
        }

        // ── STEP 5: Build output ──────────────────────────────────────────────
        List<ReviewRun> visualRuns = applyFormatSuggestionAttrsToRuns(runs, formatSuggestions);
        Delta visualDelta = buildVisualDelta(visualRuns);

        log.info("\n[REVIEW_BUILD] END");
        log.info("[REVIEW_BUILD] visualDelta opCount={}", visualDelta.ops.size());
        log.info("[REVIEW_BUILD] formatSuggestionCount={}", formatSuggestions.size());
        for (FormatSuggestionItem fmt : formatSuggestions) {
            String spanSummary = fmt.getSpans().stream()
                    .map(s -> "[" + s.getStart() + "," + (s.getStart() + s.getLength()) + "]")
                    .collect(Collectors.joining(" "));
            log.info("[REVIEW_BUILD] formatGroup={} actor={} attrKeys=\"{}\" spanCount={} spans=\"{}\" previewText=\"{}\"",
                    fmt.getGroupId(), fmt.getActorEmail(),
                    String.join(",", parseAttrs(fmt.getAttributes()).keySet()),
                    fmt.getSpans().size(), spanSummary, fmt.getPreviewText());
        }
        log.info("{}\n", "=".repeat(60));

        return new ReviewProjection(visualDelta, formatSuggestions);
    }

    // ─── applyFormatSuggestionAttrsToRuns ─────────────────────────────────────
    //
    // Clones the runs array and overlays format suggestion attributes onto the
    // appropriate runs. This is done as a final step before building the visual
    // delta so that format suggestion styling appears in the initial render.
    //
    // The original runs array is not mutated — we work on clones so the format
    // suggestion overlay can be toggled independently on the frontend without
    // recomputing the whole projection.
    //
    // Uses a binary search to find the first run overlapping each span (O(log n)
    // instead of O(n) per span).
    // ─────────────────────────────────────────────────────────────────────────
    private List<ReviewRun> applyFormatSuggestionAttrsToRuns(
            List<ReviewRun> runs,
            List<FormatSuggestionItem> formatSuggestions
    ) {
        log.debug("[APPLY_FORMAT_ATTRS_TO_RUNS] Applying {} format suggestion(s) onto {} run(s)",
                formatSuggestions.size(), runs.size());

        // Deep-clone runs so we don't mutate the pipeline state
        List<ReviewRun> cloned = runs.stream()
                .map(r -> ReviewRun.builder()
                        .text(r.getText())
                        .baseAttributes(new LinkedHashMap<>(r.getBaseAttributes() != null ? r.getBaseAttributes() : Collections.emptyMap()))
                        .suggestionAttributes(new LinkedHashMap<>(r.getSuggestionAttributes() != null ? r.getSuggestionAttributes() : Collections.emptyMap()))
                        .logicalStart(r.getLogicalStart())
                        .opId(r.getOpId())
                        .insertComponentIndex(r.getInsertComponentIndex())
                        .insertSuggestion(r.getInsertSuggestion() != null ? copyInsertSuggestion(r.getInsertSuggestion()) : null)
                        .deleteSuggestion(r.getDeleteSuggestion() != null ? copyDeleteSuggestion(r.getDeleteSuggestion()) : null)
                        .build())
                .collect(Collectors.toList());

        for (FormatSuggestionItem fmt : formatSuggestions) {
            Map<String, Object> fmtAttrs = parseAttrs(fmt.getAttributes());
            if (fmtAttrs.isEmpty()) {
                log.debug("[APPLY_FORMAT_ATTRS_TO_RUNS] groupId={} — skipping, no parseable attributes", fmt.getGroupId());
                continue;
            }

            log.debug("[APPLY_FORMAT_ATTRS_TO_RUNS] groupId={} attrKeys=\"{}\" spanCount={}",
                    fmt.getGroupId(), String.join(",", fmtAttrs.keySet()), fmt.getSpans().size());

            for (FormatSuggestionSpan span : fmt.getSpans()) {
                // Binary search for the first run overlapping this span
                int left = 0, right = cloned.size() - 1, startIdx = cloned.size();
                while (left <= right) {
                    int mid = (left + right) >>> 1;
                    ReviewRun midRun = cloned.get(mid);
                    if (midRun.getLogicalStart() + midRun.getText().length() <= span.getStart()) {
                        left = mid + 1;
                    } else {
                        startIdx = mid;
                        right = mid - 1;
                    }
                }

                for (int i = startIdx; i < cloned.size(); i++) {
                    ReviewRun run = cloned.get(i);
                    if (run.getDeleteSuggestion() != null) continue;
                    if (run.getLogicalStart() >= span.getStart() + span.getLength()) break;
                    if (run.getLogicalStart() + run.getText().length() <= span.getStart()) continue;

                    log.debug("[APPLY_FORMAT_ATTRS_TO_RUNS] groupId={} applying attrs to run text=\"{}\" logicalStart={}",
                            fmt.getGroupId(), run.getText(), run.getLogicalStart());

                    if (run.getSuggestionAttributes() == null) {
                        run.setSuggestionAttributes(new LinkedHashMap<>());
                    }
                    run.getSuggestionAttributes().putAll(fmtAttrs);
                }
            }
        }

        return cloned;
    }

    // ─── buildVisualDelta ─────────────────────────────────────────────────────
    //
    // Converts the final runs array into a Quill Delta ready for setContents().
    //
    // Pass 1 — Collapse adjacent runs that can be merged. Two runs can merge when:
    //   - Neither is a newline (newlines carry paragraph-level formatting and must
    //     remain isolated in Quill's model)
    //   - Both have identical effective attributes (base ∪ suggestion)
    //   - Both belong to the same insert suggestion group (or neither does)
    //   - Both belong to the same delete suggestion group (or neither does)
    //
    // Pass 2 — Build the delta from collapsed runs. Suggestion metadata is embedded
    // as special Quill attributes ("suggestion-insert", "suggestion-delete", etc.)
    // that registered Quill blots render as highlights/strikethroughs.
    //
    // Deleted newlines are rendered as "↵" (the return symbol) rather than "\n"
    // because inserting "\n" into Quill would create a new paragraph.
    // ─────────────────────────────────────────────────────────────────────────
    private Delta buildVisualDelta(List<ReviewRun> runs) {
        log.debug("[BUILD_VISUAL_DELTA] Building visual delta from {} run(s)", runs.size());

        List<ReviewRun> collapsed = new ArrayList<>();

        // ── Pass 1: Collapse adjacent mergeable runs ──
        for (ReviewRun run : runs) {
            ReviewRun last = collapsed.isEmpty() ? null : collapsed.get(collapsed.size() - 1);

            Map<String, Object> lastEffective = last != null
                    ? getEffectiveAttrs(last) : Collections.emptyMap();
            Map<String, Object> runEffective = getEffectiveAttrs(run);

            boolean canMerge = last != null
                    && !"\n".equals(run.getText())
                    && !"\n".equals(last.getText())
                    && attrsEq(lastEffective, runEffective)
                    && Objects.equals(
                    last.getInsertSuggestion() != null ? last.getInsertSuggestion().getGroupId() : null,
                    run.getInsertSuggestion() != null ? run.getInsertSuggestion().getGroupId() : null)
                    && Objects.equals(
                    last.getDeleteSuggestion() != null ? last.getDeleteSuggestion().getGroupId() : null,
                    run.getDeleteSuggestion() != null ? run.getDeleteSuggestion().getGroupId() : null);

            if (canMerge) {
                log.debug("[BUILD_VISUAL_DELTA] Merging run text=\"{}\" into previous text=\"{}\" (same group and attrs)",
                        run.getText(), last.getText());
                last.setText(last.getText() + run.getText());

                if (last.getInsertSuggestion() != null && run.getInsertSuggestion() != null) {
                    String mergedCreatedAt = run.getInsertSuggestion().getCreatedAt()
                            .compareTo(last.getInsertSuggestion().getCreatedAt()) > 0
                            ? run.getInsertSuggestion().getCreatedAt()
                            : last.getInsertSuggestion().getCreatedAt();
                    last.getInsertSuggestion().setCreatedAt(mergedCreatedAt);
                    last.getInsertSuggestion().setReferences(
                            mergeUniqueRefs(last.getInsertSuggestion().getReferences(),
                                    run.getInsertSuggestion().getReferences()));
                }
                if (last.getDeleteSuggestion() != null && run.getDeleteSuggestion() != null) {
                    String mergedCreatedAt = run.getDeleteSuggestion().getCreatedAt()
                            .compareTo(last.getDeleteSuggestion().getCreatedAt()) > 0
                            ? run.getDeleteSuggestion().getCreatedAt()
                            : last.getDeleteSuggestion().getCreatedAt();
                    last.getDeleteSuggestion().setCreatedAt(mergedCreatedAt);
                    last.getDeleteSuggestion().setReferences(
                            mergeUniqueRefs(last.getDeleteSuggestion().getReferences(),
                                    run.getDeleteSuggestion().getReferences()));
                }
            } else {
                log.debug("[BUILD_VISUAL_DELTA] Adding new collapsed run text=\"{}\" insertGroupId=\"{}\" deleteGroupId=\"{}\"",
                        run.getText(),
                        run.getInsertSuggestion() != null ? run.getInsertSuggestion().getGroupId() : "none",
                        run.getDeleteSuggestion() != null ? run.getDeleteSuggestion().getGroupId() : "none");
                collapsed.add(ReviewRun.builder()
                        .text(run.getText())
                        .baseAttributes(new LinkedHashMap<>(run.getBaseAttributes() != null ? run.getBaseAttributes() : Collections.emptyMap()))
                        .suggestionAttributes(new LinkedHashMap<>(run.getSuggestionAttributes() != null ? run.getSuggestionAttributes() : Collections.emptyMap()))
                        .logicalStart(run.getLogicalStart())
                        .opId(run.getOpId())
                        .insertComponentIndex(run.getInsertComponentIndex())
                        .insertSuggestion(run.getInsertSuggestion() != null ? copyInsertSuggestion(run.getInsertSuggestion()) : null)
                        .deleteSuggestion(run.getDeleteSuggestion() != null ? copyDeleteSuggestion(run.getDeleteSuggestion()) : null)
                        .build());
            }
        }

        log.debug("[BUILD_VISUAL_DELTA] Collapsed {} runs into {} ops", runs.size(), collapsed.size());

        // ── Pass 2: Build the delta from collapsed runs ──
        Delta delta = new Delta();

        for (ReviewRun run : collapsed) {
            // Effective attrs = base ∪ suggestion, suggestion wins on conflict
            Map<String, Object> attrs = new LinkedHashMap<>(
                    run.getBaseAttributes() != null ? run.getBaseAttributes() : Collections.emptyMap());
            if (run.getSuggestionAttributes() != null) {
                attrs.putAll(run.getSuggestionAttributes());
            }

            if (run.getInsertSuggestion() != null) {
                Map<String, Object> insertPayload = new LinkedHashMap<>();
                insertPayload.put("groupId", run.getInsertSuggestion().getGroupId());
                insertPayload.put("actorEmail", run.getInsertSuggestion().getActorEmail());
                insertPayload.put("createdAt", run.getInsertSuggestion().getCreatedAt());
                insertPayload.put("references", run.getInsertSuggestion().getReferences());
                attrs.put("suggestion-insert", insertPayload);
            }

            if (run.getDeleteSuggestion() != null) {
                Map<String, Object> deletePayload = new LinkedHashMap<>();
                deletePayload.put("groupId", run.getDeleteSuggestion().getGroupId());
                deletePayload.put("actorEmail", run.getDeleteSuggestion().getActorEmail());
                deletePayload.put("createdAt", run.getDeleteSuggestion().getCreatedAt());
                deletePayload.put("references", run.getDeleteSuggestion().getReferences());

                if ("\n".equals(run.getText())) {
                    // Deleted newlines get their own attribute type — the frontend
                    // "suggestion-delete-newline" blot renders as the "↵" symbol.
                    attrs.put("suggestion-delete-newline", deletePayload);
                } else {
                    attrs.put("suggestion-delete", deletePayload);
                }
            }

            // For deleted newlines, render "↵" instead of "\n" to avoid creating
            // a real paragraph break in the Quill editor.
            String textToRender = ("\n".equals(run.getText()) && run.getDeleteSuggestion() != null)
                    ? "↵"
                    : run.getText();

            if (!attrs.isEmpty()) {
                delta.insert(textToRender, attrs);
            } else {
                delta.insert(textToRender, null);
            }
        }

        log.debug("[BUILD_VISUAL_DELTA] Done. Final op count={}", delta.ops.size());
        return delta;
    }

    private void cancelInsert(String actorEmail, UUID noteId, CancelInsertPayload payload) {
        log.info("[CANCEL_INSERT] START — actor={} noteId={} insertOpId={} deleteOpId={} insertComponentIndex={} deleteComponentIndex={} overlapLength={}",
                actorEmail, noteId, payload.insertOpId(), payload.deleteOpId(),
                payload.insertComponentIndex(), payload.deleteComponentIndex(), payload.overlapLength());

        notePolicyService.validateOwner(actorEmail, noteId);
        log.info("[CANCEL_INSERT] Owner validation passed — actor={} noteId={}", actorEmail, noteId);

        NoteDto note = redisService.getNote(noteId);
        List<TextOperation> log_ops = note.revisionLog();
        log.info("[CANCEL_INSERT] Loaded revision log — opCount={}", log_ops.size());

        TextOperation insertOp = log_ops.stream()
                .filter(op -> op.getOpId().equals(payload.insertOpId()))
                .findFirst()
                .orElseThrow(() -> {
                    log.warn("[CANCEL_INSERT] ERROR — insert op not found in log: insertOpId={}", payload.insertOpId());
                    return new BadRequestException("Insert op not found: " + payload.insertOpId());
                });

        log.info("[CANCEL_INSERT] Found insertOp — opId={} actor={} state={} deltaOpCount={}",
                insertOp.getOpId(), insertOp.getActorEmail(), insertOp.getState(), insertOp.getDelta().ops.size());

        Op insertComponent = insertOp.getDelta().ops.get(payload.insertComponentIndex());
        if (insertComponent == null) {
            log.warn("[CANCEL_INSERT] ERROR — insert component not found at index={} for opId={}", payload.insertComponentIndex(), payload.insertOpId());
            throw new BadRequestException("Could not locate insert component in delta for op: " + payload.insertOpId());
        }

        log.info("[CANCEL_INSERT] Found insertComponent at componentIndex={} — type=insert text=\"{}\"",
                payload.insertComponentIndex(),
                insertComponent.getInsert() instanceof String s ? s : "[non-string]");

        int charsBeforeInsert = 0;
        {
            for (int i = 0; i < insertOp.getDelta().ops.size(); i++) {
                Op op = insertOp.getDelta().ops.get(i);

                if (op.equals(insertComponent)) break;

                if (op.isInsert() && op.getInsert() instanceof String text) {
                    charsBeforeInsert += text.length();
                } else if (op.isRetain() && op.getRetain() instanceof Integer retain) {
                    charsBeforeInsert += retain;
                }
            }
        }

        log.info("[CANCEL_INSERT] Computed charsBeforeInsert={}", charsBeforeInsert);

        String fullInsertText = (String) insertComponent.getInsert();
        int overlapLength = payload.overlapLength();
        int insertTotalLength = insertComponent.length();

        log.info("[CANCEL_INSERT] fullInsertText=\"{}\" overlapLength={} insertTotalLength={}",
                fullInsertText, overlapLength, insertTotalLength);

        if (overlapLength == insertTotalLength) {
            log.info("[CANCEL_INSERT] overlapLength == insertTotalLength — marking entire insertOp as COMMITTED opId={}", insertOp.getOpId());
            insertOp.setState(OpState.COMMITTED);
        } else {
            String committedText = fullInsertText.substring(0, overlapLength);
            String remainingText = fullInsertText.substring(overlapLength);

            log.info("[CANCEL_INSERT] Partial overlap — committedText=\"{}\" remainingText=\"{}\"", committedText, remainingText);

            Delta committedDelta = new Delta();

            if (charsBeforeInsert > 0) {
                committedDelta.retain(charsBeforeInsert, null);
                log.info("[CANCEL_INSERT] committedDelta: retain({}) prepended", charsBeforeInsert);
            }

            committedDelta.insert(committedText, insertComponent.getAttributes());
            log.info("[CANCEL_INSERT] committedDelta: insert \"{}\" added", committedText);

            TextOperation committedInsertOp = new TextOperation(
                    committedDelta,
                    insertOp.getActorEmail(),
                    insertOp.getRevision(),
                    OpState.COMMITTED,
                    insertOp.getCreatedAt()
            );
            log.info("[CANCEL_INSERT] Created committedInsertOp for actor={} revision={}", insertOp.getActorEmail(), insertOp.getRevision());

            Delta remainingDelta = new Delta();

            for (int i = 0; i < payload.insertComponentIndex(); i++) {
                remainingDelta.push(insertOp.getDelta().ops.get(i));
            }

            remainingDelta.retain(overlapLength, null);
            remainingDelta.insert(remainingText, insertComponent.getAttributes());

            log.info("[CANCEL_INSERT] remainingDelta: retain({}) + insert \"{}\" built", overlapLength, remainingText);

            for (int i = payload.insertComponentIndex() + 1; i < insertOp.getDelta().ops.size(); i++) {
                remainingDelta.push(insertOp.getDelta().ops.get(i));
            }

            insertOp.setDelta(remainingDelta);

            int insertOpIndex = log_ops.indexOf(insertOp);
            log_ops.add(insertOpIndex, committedInsertOp);
            log.info("[CANCEL_INSERT] Inserted committedInsertOp at logIndex={} — new logSize={}", insertOpIndex, log_ops.size());
        }

        TextOperation deleteOp = log_ops.stream()
                .filter(op -> op.getOpId().equals(payload.deleteOpId()))
                .findFirst()
                .orElseThrow(() -> {
                    log.warn("[CANCEL_INSERT] ERROR — delete op not found in log: deleteOpId={}", payload.deleteOpId());
                    return new BadRequestException("Delete op not found: " + payload.deleteOpId());
                });

        log.info("[CANCEL_INSERT] Found deleteOp — opId={} actor={} state={} deltaOpCount={}",
                deleteOp.getOpId(), deleteOp.getActorEmail(), deleteOp.getState(), deleteOp.getDelta().ops.size());

        Op deleteComponent = deleteOp.getDelta().ops.get(payload.deleteComponentIndex());
        if (deleteComponent == null) {
            log.warn("[CANCEL_INSERT] ERROR — delete component not found at index={} for opId={}", payload.deleteComponentIndex(), payload.deleteOpId());
            throw new BadRequestException("Could not locate delete component in delta for op: " + payload.deleteOpId());
        }

        int deleteTotalLength = deleteComponent.getDelete();
        log.info("[CANCEL_INSERT] Found deleteComponent at index={} — deleteTotalLength={}", payload.deleteComponentIndex(), deleteTotalLength);

        if (overlapLength == deleteTotalLength) {
            log.info("[CANCEL_INSERT] overlapLength == deleteTotalLength — marking entire deleteOp as COMMITTED opId={}", deleteOp.getOpId());
            deleteOp.setState(OpState.COMMITTED);
        } else {
            log.info("[CANCEL_INSERT] Partial delete overlap — splitting: committedDelete={} remainingDelete={}",
                    overlapLength, deleteTotalLength - overlapLength);

            Delta committedDeleteDelta = new Delta();

            for (int i = 0; i < payload.deleteComponentIndex(); i++) {
                committedDeleteDelta.push(deleteOp.getDelta().ops.get(i));
            }

            committedDeleteDelta.delete(overlapLength);
            log.info("[CANCEL_INSERT] committedDeleteDelta: delete({}) built", overlapLength);

            TextOperation committedDeleteOp = new TextOperation(
                    committedDeleteDelta,
                    deleteOp.getActorEmail(),
                    deleteOp.getRevision(),
                    OpState.COMMITTED,
                    deleteOp.getCreatedAt()
            );
            log.info("[CANCEL_INSERT] Created committedDeleteOp for actor={} revision={}", deleteOp.getActorEmail(), deleteOp.getRevision());

            Delta remainingDeleteDelta = new Delta();

            for (int i = 0; i < payload.deleteComponentIndex(); i++) {
                remainingDeleteDelta.push(deleteOp.getDelta().ops.get(i));
            }

            remainingDeleteDelta.delete(deleteTotalLength - overlapLength);
            log.info("[CANCEL_INSERT] remainingDeleteDelta: delete({}) built", deleteTotalLength - overlapLength);

            for (int i = payload.deleteComponentIndex() + 1; i < deleteOp.getDelta().ops.size(); i++) {
                remainingDeleteDelta.push(deleteOp.getDelta().ops.get(i));
            }

            deleteOp.setDelta(remainingDeleteDelta);

            int deleteOpIndex = log_ops.indexOf(deleteOp);
            log_ops.add(deleteOpIndex, committedDeleteOp);
            log.info("[CANCEL_INSERT] Inserted committedDeleteOp at logIndex={} — new logSize={}", deleteOpIndex, log_ops.size());
        }

        NoteVersionDto noteVersion = redisService.getNoteVersion(noteId);
        log.info("[CANCEL_INSERT] Fetched NoteVersion for noteId={}", noteId);

        NoteDto updatedNote = new NoteDto(
                note.id(),
                note.ownerEmail(),
                note.title(),
                log_ops,
                note.visibility(),
                note.accessRole(),
                note.currentNoteVersionNumber(),
                note.createdAt(),
                note.updatedAt()
        );

        redisService.updateNote(updatedNote, noteVersion);
        log.info("[CANCEL_INSERT] Note updated in Redis — noteId={}", noteId);

        noteService.saveNote(actorEmail, noteId);
        log.info("[CANCEL_INSERT] Note saved to persistent store — actor={} noteId={}", actorEmail, noteId);
        log.info("[CANCEL_INSERT] END — actor={} noteId={} insertOpId={}", actorEmail, noteId, payload.insertOpId());
    }

    private void cancelFormat(String actorEmail, UUID noteId, CancelFormatPayload payload) {
        log.info("[CANCEL_FORMAT] START — actor={} noteId={} cancellingOpId={} retainComponentIndex={} opLength={} consumedBefore={} targetReferenceCount={}",
                actorEmail, noteId, payload.cancellingOpId(), payload.retainComponentIndex(),
                payload.opLength(), payload.consumedBefore(), payload.targetReferences().size());

        notePolicyService.validateOwner(actorEmail, noteId);
        log.info("[CANCEL_FORMAT] Owner validation passed — actor={} noteId={}", actorEmail, noteId);

        NoteDto note = redisService.getNote(noteId);
        List<TextOperation> log_ops = note.revisionLog();
        log.info("[CANCEL_FORMAT] Loaded revision log — opCount={}", log_ops.size());

        TextOperation cancellingOp = log_ops.stream()
                .filter(op -> op.getOpId().equals(payload.cancellingOpId()))
                .findFirst()
                .orElseThrow(() -> {
                    log.warn("[CANCEL_FORMAT] ERROR — cancelling op not found: cancellingOpId={}", payload.cancellingOpId());
                    return new BadRequestException("Cancelling op not found: " + payload.cancellingOpId());
                });

        log.info("[CANCEL_FORMAT] Found cancellingOp — opId={} actor={} state={} deltaOpCount={}",
                cancellingOp.getOpId(), cancellingOp.getActorEmail(), cancellingOp.getState(), cancellingOp.getDelta().ops.size());

        Op cancellingRetain = cancellingOp.getDelta().ops.get(payload.retainComponentIndex());
        if (cancellingRetain == null || !cancellingRetain.isRetain()) {
            log.warn("[CANCEL_FORMAT] ERROR — retain component not found or not a retain at index={} for opId={}",
                    payload.retainComponentIndex(), payload.cancellingOpId());
            throw new BadRequestException(
                    "Could not locate retain component at index "
                            + payload.retainComponentIndex()
                            + " for op: " + payload.cancellingOpId()
            );
        }

        int cancellingRetainTotal = (Integer) cancellingRetain.getRetain();
        log.info("[CANCEL_FORMAT] Found cancellingRetain at index={} — retainLength={}", payload.retainComponentIndex(), cancellingRetainTotal);

        int overlapLen = payload.opLength();

        // Split each affected pending format op
        for (OpReference ref : payload.targetReferences()) {
            log.info("[CANCEL_FORMAT] Processing targetReference — refOpId={} refComponentIndex={}", ref.opId(), ref.componentIndex());

            TextOperation targetOp = log_ops.stream()
                    .filter(op -> op.getOpId().equals(ref.opId()))
                    .findFirst()
                    .orElse(null);

            if (targetOp == null || targetOp.getState() != OpState.PENDING) {
                log.info("[CANCEL_FORMAT] Skipping refOpId={} — notFound={} state={}",
                        ref.opId(), targetOp == null, targetOp != null ? targetOp.getState() : "N/A");
                continue;
            }

            log.info("[CANCEL_FORMAT] Found targetOp — opId={} actor={} state={}",
                    targetOp.getOpId(), targetOp.getActorEmail(), targetOp.getState());

            Delta originalTargetDelta = targetOp.getDelta();
            Op targetRetain = originalTargetDelta.ops.get(ref.componentIndex());

            if (targetRetain == null) {
                log.warn("[CANCEL_FORMAT] ERROR — target retain not found at componentIndex={} for opId={}", ref.componentIndex(), ref.opId());
                throw new BadRequestException(
                        "Could not locate target retain component at index "
                                + ref.componentIndex()
                                + " for op: " + ref.opId()
                );
            }

            int fullLen = (Integer) targetRetain.getRetain();
            int consumed = Math.min(payload.consumedBefore() + overlapLen, fullLen);
            int remainingLen = fullLen - consumed;

            log.info("[CANCEL_FORMAT] targetRetain fullLen={} consumedBefore={} overlapLen={} consumed={} remainingLen={}",
                    fullLen, payload.consumedBefore(), overlapLen, consumed, remainingLen);

            if (consumed <= 0) {
                log.info("[CANCEL_FORMAT] consumed={} <= 0 — skipping this reference", consumed);
                continue;
            }

            if (remainingLen == 0) {
                log.info("[CANCEL_FORMAT] remainingLen=0 — marking targetOp as COMMITTED opId={}", targetOp.getOpId());
                targetOp.setState(OpState.COMMITTED);
            } else {
                Delta pendingRemainderDelta = new Delta();
                for (int i = 0; i < ref.componentIndex(); i++) {
                    pendingRemainderDelta.push(originalTargetDelta.ops.get(i));
                }

                pendingRemainderDelta.retain(consumed, null);
                pendingRemainderDelta.retain(remainingLen, targetRetain.getAttributes());

                for (int i = ref.componentIndex() + 1; i < originalTargetDelta.ops.size(); i++) {
                    pendingRemainderDelta.push(originalTargetDelta.ops.get(i));
                }

                targetOp.setDelta(pendingRemainderDelta);
                log.info("[CANCEL_FORMAT] Updated targetOp delta — retain({}, null) + retain({}, attrs) for opId={}",
                        consumed, remainingLen, targetOp.getOpId());
            }
        }

        // Persist the cancelling op split
        int cancellingConsumed = payload.consumedBefore() + overlapLen;
        log.info("[CANCEL_FORMAT] Splitting cancellingOp — cancellingConsumed={} cancellingTotal={}",
                cancellingConsumed, cancellingRetainTotal);

        if (cancellingConsumed >= cancellingRetainTotal) {
            log.info("[CANCEL_FORMAT] cancellingConsumed >= total — marking cancellingOp as COMMITTED opId={}", cancellingOp.getOpId());
            cancellingOp.setState(OpState.COMMITTED);
        } else {
            int cancellingRemainder = cancellingRetainTotal - cancellingConsumed;
            log.info("[CANCEL_FORMAT] Partial cancelling split — committedPortion={} remainderPortion={}", cancellingConsumed, cancellingRemainder);

            Delta committedDelta = new Delta();
            for (int i = 0; i < payload.retainComponentIndex(); i++) {
                committedDelta.push(cancellingOp.getDelta().ops.get(i));
            }
            committedDelta.retain(cancellingConsumed, cancellingRetain.getAttributes());

            log.info("[CANCEL_FORMAT] committedDelta: retain({}, attrs) built", cancellingConsumed);

            TextOperation committedPart = new TextOperation(
                    committedDelta,
                    cancellingOp.getActorEmail(),
                    cancellingOp.getRevision(),
                    OpState.COMMITTED,
                    cancellingOp.getCreatedAt()
            );
            log.info("[CANCEL_FORMAT] Created committedPart for actor={} revision={}", cancellingOp.getActorEmail(), cancellingOp.getRevision());

            Delta remainingDelta = new Delta();
            for (int i = 0; i < payload.retainComponentIndex(); i++) {
                remainingDelta.push(cancellingOp.getDelta().ops.get(i));
            }
            remainingDelta.retain(cancellingConsumed, null);
            remainingDelta.retain(cancellingRemainder, cancellingRetain.getAttributes());
            for (int i = payload.retainComponentIndex() + 1; i < cancellingOp.getDelta().ops.size(); i++) {
                remainingDelta.push(cancellingOp.getDelta().ops.get(i));
            }

            cancellingOp.setDelta(remainingDelta);
            log.info("[CANCEL_FORMAT] Updated cancellingOp delta — retain({}, null) + retain({}, attrs)", cancellingConsumed, cancellingRemainder);

            int cancellingIndex = log_ops.indexOf(cancellingOp);
            log_ops.add(cancellingIndex, committedPart);
            log.info("[CANCEL_FORMAT] Inserted committedPart at logIndex={} — new logSize={}", cancellingIndex, log_ops.size());
        }

        NoteVersionDto noteVersion = redisService.getNoteVersion(noteId);
        log.info("[CANCEL_FORMAT] Fetched NoteVersion for noteId={}", noteId);

        NoteDto updatedNote = new NoteDto(
                note.id(),
                note.ownerEmail(),
                note.title(),
                log_ops,
                note.visibility(),
                note.accessRole(),
                note.currentNoteVersionNumber(),
                note.createdAt(),
                note.updatedAt()
        );

        redisService.updateNote(updatedNote, noteVersion);
        log.info("[CANCEL_FORMAT] Note updated in Redis — noteId={}", noteId);

        noteService.saveNote(actorEmail, noteId);
        log.info("[CANCEL_FORMAT] Note saved to persistent store — actor={} noteId={}", actorEmail, noteId);
        log.info("[CANCEL_FORMAT] END — actor={} noteId={} cancellingOpId={}", actorEmail, noteId, payload.cancellingOpId());
    }
}
