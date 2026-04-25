package com.example.notes.services.impl;

import com.example.notes.dto.attribution.*;
import com.example.notes.dto.note.CancelFormatPayload;
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
//   - baseDelta    : the base document as a plain Quill delta (inserts only)
//   - visualDelta       : a retain-based Quill delta the frontend applies on top of
//                         baseDelta via updateContents(). Uses retains instead
//                         of inserts so that suggestion attrs never bleed into
//                         surrounding committed text.
//   - formatSuggestions : all format suggestion groups for the sidebar panel
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
//     Apply format suggestion attrs onto cloned runs, then build:
//       - baseDelta : plain base document (inserts)
//       - visualDelta    : retain-based overlay with suggestion attrs + base-attributes
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
    // @return              ReviewProjection with baseDelta + visualDelta + formatSuggestions
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public ReviewProjection buildReviewProjection(
            String actorEmail, UUID noteId
    ) {
        notePolicyService.validateOwner(actorEmail, noteId);

        NoteDto note = redisService.getNote(noteId);

        List<TextOperation> committedTextOps = note.revisionLog().stream()
                .filter(textOp -> textOp.getState().equals(OpState.COMMITTED))
                .toList();
        List<TextOperation> pendingTextOps = note.revisionLog().stream()
                .filter(textOp -> textOp.getState().equals(OpState.PENDING))
                .toList();

        // Reset the group ID counter so IDs are predictable (g_1, g_2...) for each build
        resetGroupCounter();

        // ── STEP 1: Seed runs from committed document ──────────────────────────

        // Compose all committed ops into one base delta representing the current
        // committed document state. compose() applies each op on top of the previous.
        Delta committedDelta = new Delta();
        for (TextOperation textOp : committedTextOps) {
            committedDelta = committedDelta.compose(new Delta(textOp.getDelta().ops));
        }

        // Break the committed delta into individual runs. Text segments are split on
        // "\n" so that each newline becomes its own run — required because Quill treats
        // "\n" as a paragraph terminator carrying block-level formatting.
        //
        // baseAttributes on each run represents exactly what the text had at insert/commit
        // time. It is never modified after seeding (except for new insert runs, which also
        // set baseAttributes to the insert-time attrs). This gives us a reliable "previous
        // state" for undo: to undo a bold:null suggestion, read baseAttributes.bold → true.
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
                            .build());
                    seedPos += 1;
                }
            }
        }

        // ── STEP 2: Apply pending ops ──────────────────────────────────────────

        List<FormatSuggestionItem> formatSuggestions = new ArrayList<>();

        // Accumulate format cancellations to flush after the run loop.
        List<PendingFormatCancellation> pendingFormatCancellations = new ArrayList<>();

        for (TextOperation textOp : pendingTextOps) {
            String opId = textOp.getOpId();
            String authorEmail = textOp.getActorEmail();
            String createdAt = textOp.getCreatedAt().toString();

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

                    // The trailing retain that Quill appends to every delta (to fill out
                    // the document length) has no semantic meaning — skip it.
                    if (isLastOp) {
                        break;
                    }

                    currentInsertGroup = null;
                    currentDeleteGroup = null;

                    boolean newlineOnly = isOnlyNewlineRetain(runs, localLogPos, retainLen);
                    if (newlineOnly && currentFormatGroup != null) {
                        // A newline-only retain between two format retains means the format
                        // spans a paragraph break. Extend the current format group's span to
                        // include the newline, and set the bridge for reconnection on the next
                        // format retain.
                        RunPosition absPosResult = findRunPos(runs, localLogPos);
                        RunPosition nextAbsPosResult = findRunPos(runs, localLogPos + retainLen);
                        int absPos = absPosResult.absPos();
                        int absLength = nextAbsPosResult.absPos() - absPos;

                        currentFormatGroup.setSpans(extendOrAddSpan(
                                currentFormatGroup.getSpans(), absPos, absLength));

                        pendingFormatBridge = new HashMap<>();
                        pendingFormatBridge.put("actorEmail", authorEmail);
                        pendingFormatBridge.put("attributes", currentFormatGroup.getAttributes());
                        pendingFormatBridge.put("groupId", currentFormatGroup.getGroupId());
                    } else {
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

                    RunPosition startPos = findRunPos(runs, localLogPos);
                    int runIdx = startPos.idx();
                    int startOffset = startPos.offset();

                    if (startOffset > 0 && runIdx < runs.size()) {
                        runIdx = splitAt(runs, runIdx, startOffset);
                    }

                    int remaining = retainLen;
                    int cursor = runIdx;

                    while (remaining > 0 && cursor < runs.size()) {
                        ReviewRun run = runs.get(cursor);

                        if (run.getDeleteSuggestion() != null) {
                            cursor++;
                            continue;
                        }

                        if ("\n".equals(run.getText())) {
                            cursor++;

                            if (currentFormatGroup != null) {
                                pendingFormatBridge = new HashMap<>();
                                pendingFormatBridge.put("actorEmail", authorEmail);
                                pendingFormatBridge.put("attributes", currentFormatGroup.getAttributes());
                                pendingFormatBridge.put("groupId", currentFormatGroup.getGroupId());
                            }
                            continue;
                        }

                        if (run.getText().length() > remaining) {
                            splitAt(runs, cursor, remaining);
                        }

                        ReviewRun target = runs.get(cursor);
                        int spanStart = target.getLogicalStart();
                        int spanLen = target.getText().length();

                        Map<String, Object> rawIncomingAttrs = new LinkedHashMap<>(componentAttrs);

                        final int finalSpanStart = spanStart;
                        final int finalSpanLen = spanLen;
                        List<FormatSuggestionItem> coveringFormats = formatSuggestions.stream()
                                .filter(f -> f.getSpans().stream().anyMatch(s ->
                                        s.getStart() <= finalSpanStart
                                                && s.getStart() + s.getLength() >= finalSpanStart + finalSpanLen))
                                .toList();

                        for (FormatSuggestionItem fmt : new ArrayList<>(coveringFormats)) {
                            Map<String, Object> fmtAttrs = parseAttrs(fmt.getAttributes());
                            // Use baseAttributes (insert-time attrs) as the authoritative
                            // "before suggestion" state when classifying key changes.
                            // This is why baseAttributes must never be mutated after seeding.
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
                                } else {
                                    pendingFormatCancellations.add(PendingFormatCancellation.builder()
                                            .groupId(fmt.getGroupId())
                                            .references(refsFromSlices(fmt.getReferences()))
                                            .cancellingOpId(opId)
                                            .retainComponentIndex(compIdx)
                                            .consumedBefore(consumedBefore)
                                            .length(spanLen)
                                            .build());
                                }

                                removeRangeFromFormatSuggestion(fmt, spanStart, spanLen);

                                if (fmt.getSpans().isEmpty()) {
                                    formatSuggestions.remove(fmt);
                                }

                                // Remove cancelled keys from suggestionAttributes only —
                                // baseAttributes is never touched here.
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

                        // Pending format attrs go into suggestionAttributes, never baseAttributes.
                        target.setSuggestionAttributes(
                                overlayAttrsPreserveNull(
                                        target.getSuggestionAttributes(),
                                        rawIncomingAttrs
                                )
                        );

                        String attrStr = attrsToJson(rawIncomingAttrs);

                        if (!rawIncomingAttrs.isEmpty()) {
                            if (currentFormatGroup == null) {
                                final String finalAttrStr = attrStr;
                                final String finalActorEmail = authorEmail;
                                final int finalSpanStart2 = spanStart;
                                final int finalSpanEnd2 = spanStart + spanLen;

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
                                    existing = prevAdj;

                                    if (nextAdj != null && !nextAdj.getGroupId().equals(prevAdj.getGroupId())) {
                                        existing.setReferences(mergeSuggestionSlices(
                                                existing.getReferences(),
                                                nextAdj.getReferences()
                                        ));

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
                                    }

                                } else if (nextAdj != null) {
                                    existing = nextAdj;
                                } else if (pendingFormatBridge != null
                                        && authorEmail.equals(pendingFormatBridge.get("actorEmail"))
                                        && attrStr.equals(pendingFormatBridge.get("attributes"))) {
                                    final String bridgeGroupId = pendingFormatBridge.get("groupId");
                                    existing = formatSuggestions.stream()
                                            .filter(f -> f.getGroupId().equals(bridgeGroupId))
                                            .findFirst()
                                            .orElse(null);
                                }

                                if (existing == null) {
                                    existing = FormatSuggestionItem.builder()
                                            .groupId(nextId())
                                            .actorEmail(authorEmail)
                                            .createdAt(createdAt)
                                            .attributes(attrStr)
                                            .references(new ArrayList<>())
                                            .spans(new ArrayList<>())
                                            .previewText("")
                                            .dependsOnInsertGroupIds(new ArrayList<>())
                                            .build();
                                    formatSuggestions.add(existing);
                                }

                                currentFormatGroup = existing;
                            }

                            if (target.getInsertSuggestion() != null
                                    && !currentFormatGroup.getDependsOnInsertGroupIds()
                                    .contains(target.getInsertSuggestion().getGroupId())) {
                                currentFormatGroup.getDependsOnInsertGroupIds()
                                        .add(target.getInsertSuggestion().getGroupId());
                            }

                            int componentLocalStart = retainLen - remaining;
                            currentFormatGroup.setReferences(addComponentLocalSlice(
                                    currentFormatGroup.getReferences(),
                                    componentLocalStart,
                                    spanLen,
                                    opId,
                                    compIdx
                            ));

                            int adjacentIdx = findAdjacentSpanIndex(currentFormatGroup.getSpans(), spanStart);
                            if (adjacentIdx != -1) {
                                currentFormatGroup.getSpans().get(adjacentIdx)
                                        .setLength(currentFormatGroup.getSpans().get(adjacentIdx).getLength() + spanLen);
                                currentFormatGroup.setSpans(mergeAdjacentSpans(
                                        currentFormatGroup.getSpans().stream()
                                                .map(s -> FormatSuggestionSpan.builder()
                                                        .start(s.getStart()).length(s.getLength()).build())
                                                .collect(Collectors.toList())));
                            } else {
                                currentFormatGroup.getSpans().add(
                                        FormatSuggestionSpan.builder().start(spanStart).length(spanLen).build());
                                currentFormatGroup.setSpans(mergeAdjacentSpans(
                                        currentFormatGroup.getSpans().stream()
                                                .map(s -> FormatSuggestionSpan.builder()
                                                        .start(s.getStart()).length(s.getLength()).build())
                                                .collect(Collectors.toList())));
                            }

                            pendingFormatBridge = new HashMap<>();
                            pendingFormatBridge.put("actorEmail", authorEmail);
                            pendingFormatBridge.put("attributes", attrStr);
                            pendingFormatBridge.put("groupId", currentFormatGroup.getGroupId());

                        }

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

                    RunPosition insertPos = findRunPos(runs, localLogPos);
                    int runIndex = insertPos.idx();
                    int insertOffset = insertPos.offset();
                    int insertAbsPos = insertPos.absPos();

                    int insertAtIdx = runIndex;
                    if (insertOffset > 0 && runIndex < runs.size()) {
                        insertAtIdx = splitAt(runs, runIndex, insertOffset);
                    }

                    ReviewRun prevRun = (insertAtIdx > 0) ? runs.get(insertAtIdx - 1) : null;
                    ReviewRun nextRun = (insertAtIdx < runs.size()) ? runs.get(insertAtIdx) : null;

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
                        } else {
                            currentInsertGroup = InsertSuggestion.builder()
                                    .groupId(nextId())
                                    .actorEmail(authorEmail)
                                    .createdAt(createdAt)
                                    .references(new ArrayList<>())
                                    .startIndex(localLogPos)
                                    .build();
                        }
                    } else if (createdAt.compareTo(currentInsertGroup.getCreatedAt()) > 0) {
                        currentInsertGroup.setCreatedAt(createdAt);
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
                    }

                    // ── Check prev neighbor for inherited attrs (different actor) ──
                    Map<String, Object> prevEffectiveAttrs = getEffectiveAttrs(prevRun);
                    Map<String, Object> nextEffectiveAttrs = getEffectiveAttrs(nextRun);

                    if (!ownAttrs.isEmpty()
                            && prevRun != null
                            && prevRun.getInsertSuggestion() != null
                            && !authorEmail.equals(prevRun.getInsertSuggestion().getActorEmail())
                            && !prevEffectiveAttrs.isEmpty()) {

                        Map<String, Object> inherited = intersectAttrs(ownAttrs, prevEffectiveAttrs);

                        if (!inherited.isEmpty()) {
                            String attrStr = attrsToJson(inherited);
                            InsertGroupCollection prevGroup = collectInsertGroupRunsWithAttrs(
                                    runs, prevRun.getInsertSuggestion().getGroupId(), inherited);

                            if (prevGroup != null) {
                                int spanStart = prevGroup.start;
                                int spanEnd = localLogPos + insertText.length();

                                String ownerEmail = prevRun.getInsertSuggestion().getActorEmail();

                                FormatSuggestionItem g = FormatSuggestionItem.builder()
                                        .groupId(nextId())
                                        .actorEmail(ownerEmail)
                                        .createdAt(prevRun.getInsertSuggestion().getCreatedAt())
                                        .attributes(attrStr)
                                        .references(new ArrayList<>())
                                        .spans(new ArrayList<>(Collections.singletonList(
                                                FormatSuggestionSpan.builder().start(spanStart).length(spanEnd - spanStart).build())))
                                        .previewText("")
                                        .dependsOnInsertGroupIds(new ArrayList<>(Arrays.asList(
                                                prevRun.getInsertSuggestion().getGroupId(),
                                                currentInsertGroup.getGroupId())))
                                        .build();

                                g.setReferences(mergeSuggestionSlices(
                                        g.getReferences(),
                                        prevRun.getInsertSuggestion().getReferences()
                                ));

                                g.setReferences(addComponentLocalSlice(
                                        g.getReferences(),
                                        0,
                                        insertText.length(),
                                        opId,
                                        compIdx
                                ));

                                formatSuggestions.add(g);
                                extendedGroupIds.add(g.getGroupId());

                                stripAttrsFromRuns(runs, prevGroup.indices, inherited);
                                ownAttrs = subtractAttrs(ownAttrs, inherited);
                            }
                        }
                    }

                    // ── Check next neighbor for inherited attrs (different actor) ──
                    if (!ownAttrs.isEmpty()
                            && nextRun != null
                            && nextRun.getInsertSuggestion() != null
                            && !authorEmail.equals(nextRun.getInsertSuggestion().getActorEmail())
                            && !nextEffectiveAttrs.isEmpty()) {

                        Map<String, Object> inherited = intersectAttrs(ownAttrs, nextEffectiveAttrs);

                        if (!inherited.isEmpty()) {
                            String ownerEmail = nextRun.getInsertSuggestion().getActorEmail();
                            String attrStr = attrsToJson(inherited);
                            InsertGroupCollection nextGroup = collectInsertGroupRunsWithAttrs(
                                    runs, nextRun.getInsertSuggestion().getGroupId(), inherited);

                            if (nextGroup != null) {
                                int spanStart = localLogPos;
                                int spanEnd = nextGroup.end;

                                FormatSuggestionItem g = FormatSuggestionItem.builder()
                                        .groupId(nextId())
                                        .actorEmail(ownerEmail)
                                        .createdAt(nextRun.getInsertSuggestion().getCreatedAt())
                                        .attributes(attrStr)
                                        .references(cloneSuggestionSlices(nextRun.getInsertSuggestion().getReferences()))
                                        .spans(new ArrayList<>(Collections.singletonList(
                                                FormatSuggestionSpan.builder().start(spanStart).length(spanEnd - spanStart).build())))
                                        .previewText("")
                                        .dependsOnInsertGroupIds(new ArrayList<>(Arrays.asList(
                                                nextRun.getInsertSuggestion().getGroupId(),
                                                currentInsertGroup.getGroupId())))
                                        .build();

                                g.setReferences(addSuggestionSlice(
                                        g.getReferences(),
                                        localLogPos,
                                        insertText.length(),
                                        opId,
                                        compIdx
                                ));

                                formatSuggestions.add(g);
                                extendedGroupIds.add(g.getGroupId());

                                stripAttrsFromRuns(runs, nextGroup.indices, inherited);
                                ownAttrs = subtractAttrs(ownAttrs, inherited);
                            }
                        }
                    }

                    // ── Splice new runs into the runs list ───────────────────────
                    // Split insertText on "\n" so each newline becomes its own run.
                    // baseAttributes for new insert runs = the insert-time attrs (ownAttrs).
                    // This is the insert-time snapshot — consistent with committed run seeding.
                    int componentLocalInsertCursor = 0;
                    String[] parts = insertText.split("\n", -1);
                    int spliceAt = insertAtIdx;
                    int runPos = insertAbsPos;

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

                                InsertSuggestion merged = copyInsertSuggestion(prevInsertedRun.getInsertSuggestion());

                                merged.setReferences(addComponentLocalSlice(
                                        merged.getReferences(),
                                        componentLocalInsertCursor,
                                        parts[i].length(),
                                        opId,
                                        compIdx
                                ));

                                if (createdAt.compareTo(merged.getCreatedAt()) > 0) {
                                    merged.setCreatedAt(createdAt);
                                }

                                prevInsertedRun.setInsertSuggestion(merged);
                            } else {
                                InsertSuggestion runSuggestion = copyInsertSuggestion(currentInsertGroup);

                                runSuggestion.setReferences(addComponentLocalSlice(
                                        runSuggestion.getReferences(),
                                        componentLocalInsertCursor,
                                        parts[i].length(),
                                        opId,
                                        compIdx
                                ));

                                ReviewRun newRun = ReviewRun.builder()
                                        .text(parts[i])
                                        .baseAttributes(new LinkedHashMap<>(ownAttrs))
                                        .suggestionAttributes(new LinkedHashMap<>())
                                        .logicalStart(runPos)
                                        .insertSuggestion(runSuggestion)
                                        .build();

                                runs.add(spliceAt++, newRun);
                            }

                            componentLocalInsertCursor += parts[i].length();
                            runPos += parts[i].length();
                        }

                        if (i < parts.length - 1) {
                            InsertSuggestion newlineSuggestion = copyInsertSuggestion(currentInsertGroup);

                            newlineSuggestion.setReferences(addComponentLocalSlice(
                                    newlineSuggestion.getReferences(),
                                    componentLocalInsertCursor,
                                    1,
                                    opId,
                                    compIdx
                            ));

                            ReviewRun newlineRun = ReviewRun.builder()
                                    .text("\n")
                                    .baseAttributes(new LinkedHashMap<>())
                                    .suggestionAttributes(new LinkedHashMap<>())
                                    .logicalStart(runPos)
                                    .insertSuggestion(newlineSuggestion)
                                    .build();

                            componentLocalInsertCursor += 1;
                            runs.add(spliceAt++, newlineRun);
                            runPos += 1;
                        }
                    }

                    // Shift all subsequent runs right to fill the inserted space
                    int shiftLen = insertText.length();
                    for (int i = spliceAt; i < runs.size(); i++) {
                        runs.get(i).setLogicalStart(runs.get(i).getLogicalStart() + shiftLen);
                    }

                    // Shift format spans to account for the inserted text
                    shiftFormatSpansForInsert(formatSuggestions, insertAbsPos, insertText.length(), extendedGroupIds);

                    localLogPos += insertText.length();

                    // ── Delete ────────────────────────────────────────────────────
                    // Text is being deleted. Rather than removing runs, we mark them
                    // with a deleteSuggestion so they remain visible in review mode.
                } else if (component.isDelete()) {
                    currentInsertGroup = null;
                    currentFormatGroup = null;

                    RunPosition deletePos = findRunPos(runs, localLogPos);
                    int ri = deletePos.idx();
                    int deleteOffset = deletePos.offset();
                    int cursor = ri;

                    if (deleteOffset > 0 && ri < runs.size()) {
                        cursor = splitAt(runs, ri, deleteOffset);
                    }

                    if (currentDeleteGroup == null) {
                        ReviewRun prevRunD = (cursor > 0) ? runs.get(cursor - 1) : null;
                        ReviewRun nextRunD = (cursor < runs.size()) ? runs.get(cursor) : null;

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
                            currentDeleteGroup = copyDeleteSuggestion(prevAdj);

                            if (nextAdj != null && !nextAdj.getGroupId().equals(prevAdj.getGroupId())) {
                                currentDeleteGroup.setReferences(mergeSuggestionSlices(
                                        currentDeleteGroup.getReferences(),
                                        nextAdj.getReferences()
                                ));

                                if (nextAdj.getCreatedAt().compareTo(currentDeleteGroup.getCreatedAt()) > 0) {
                                    currentDeleteGroup.setCreatedAt(nextAdj.getCreatedAt());
                                }

                                for (ReviewRun existingRun : runs) {
                                    if (existingRun.getDeleteSuggestion() != null
                                            && nextAdj.getGroupId().equals(existingRun.getDeleteSuggestion().getGroupId())) {
                                        existingRun.setDeleteSuggestion(copyDeleteSuggestion(currentDeleteGroup));
                                    }
                                }
                            }

                        } else if (nextAdj != null) {
                            currentDeleteGroup = copyDeleteSuggestion(nextAdj);

                        } else {
                            currentDeleteGroup = DeleteSuggestion.builder()
                                    .groupId(nextId())
                                    .actorEmail(authorEmail)
                                    .createdAt(createdAt)
                                    .references(new ArrayList<>())
                                    .build();
                        }
                    }

                    int remaining = component.getDelete();
                    int deleteComponentLength = component.getDelete();

                    while (remaining > 0 && cursor < runs.size()) {
                        ReviewRun run = runs.get(cursor);
                        int deleteComponentLocalStart = deleteComponentLength - remaining;

                        if (run.getDeleteSuggestion() != null) {
                            cursor++;
                            continue;
                        }

                        if ("\n".equals(run.getText()) && run.getInsertSuggestion() == null) {
                            DeleteSuggestion newlineDelete = copyDeleteSuggestion(currentDeleteGroup);

                            newlineDelete.setReferences(addComponentLocalSlice(
                                    newlineDelete.getReferences(),
                                    deleteComponentLocalStart,
                                    1,
                                    opId,
                                    compIdx
                            ));

                            run.setDeleteSuggestion(newlineDelete);

                            remaining--;
                            localLogPos++;
                            cursor++;
                            continue;
                        }

                        if (remaining < run.getText().length()) {
                            splitAt(runs, cursor, remaining);
                        }

                        ReviewRun target = runs.get(cursor);
                        int len = target.getText().length();

                        if (target.getInsertSuggestion() != null) {
                            List<SuggestionSlice> targetSlices =
                                    cloneSuggestionSlices(target.getInsertSuggestion().getReferences());

                            runs.remove(cursor);
                            for (int i = cursor; i < runs.size(); i++) {
                                runs.get(i).setLogicalStart(runs.get(i).getLogicalStart() - len);
                            }

                            cancelInsert(
                                    actorEmail,
                                    noteId,
                                    targetSlices,
                                    opId,
                                    compIdx
                            );

                            remaining -= len;
                            localLogPos += len;
                            continue;
                        }

                        DeleteSuggestion runDelete = copyDeleteSuggestion(currentDeleteGroup);

                        runDelete.setReferences(addComponentLocalSlice(
                                runDelete.getReferences(),
                                deleteComponentLocalStart,
                                len,
                                opId,
                                compIdx
                        ));

                        target.setDeleteSuggestion(runDelete);

                        remaining -= len;
                        localLogPos += len;
                        cursor++;
                    }
                }
            }
        }

        // ── STEP 3: Build preview texts ───────────────────────────────────────
        for (FormatSuggestionItem fmt : formatSuggestions) {
            if (fmt.getPreviewText() != null && !fmt.getPreviewText().isEmpty()) {
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
        }

        // ── STEP 4: Flush pending format cancellations ────────────────────────
        for (PendingFormatCancellation c : pendingFormatCancellations) {
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
        //
        // baseDelta: the plain base document — used by the frontend as
        //   the base for setContents(). Contains only base inserts with their
        //   real formatting. No suggestion metadata.
        //
        // visualDelta: a retain-based delta the frontend applies on top of baseDelta
        //   via updateContents(). Using retains (not inserts) prevents suggestion attrs
        //   from bleeding into surrounding committed text. Each retain op carries:
        //     - suggestion-insert / suggestion-delete / suggestion-delete-newline
        //     - base-attributes: the run's committed formatting snapshot
        //     - suggestion-attributes: the pending format attrs (if any)
        //
        // For deleted committed runs, the run must appear as an insert of "↵" in the
        // visual delta (since the committed document doesn't contain them, there is
        // nothing to retain at that position).

        Delta baseDelta = new Delta();
        for (TextOperation textOp : note.revisionLog()) {
            baseDelta = baseDelta.compose(new Delta(textOp.getDelta().ops));
        }

        List<ReviewRun> visualRuns = applyFormatSuggestionAttrsToRuns(runs, formatSuggestions);
        Delta visualDelta = buildVisualDelta(visualRuns);

        return new ReviewProjection(baseDelta, visualDelta, formatSuggestions);
    }

    // ─── applyFormatSuggestionAttrsToRuns ─────────────────────────────────────
    //
    // Clones the runs array and overlays format suggestion attributes onto the
    // appropriate runs (into suggestionAttributes only — never baseAttributes).
    // ─────────────────────────────────────────────────────────────────────────
    private List<ReviewRun> applyFormatSuggestionAttrsToRuns(
            List<ReviewRun> runs,
            List<FormatSuggestionItem> formatSuggestions
    ) {
        List<ReviewRun> cloned = runs.stream()
                .map(r -> ReviewRun.builder()
                        .text(r.getText())
                        .baseAttributes(new LinkedHashMap<>(r.getBaseAttributes() != null ? r.getBaseAttributes() : Collections.emptyMap()))
                        .suggestionAttributes(new LinkedHashMap<>(r.getSuggestionAttributes() != null ? r.getSuggestionAttributes() : Collections.emptyMap()))
                        .logicalStart(r.getLogicalStart())
                        .insertSuggestion(r.getInsertSuggestion() != null ? copyInsertSuggestion(r.getInsertSuggestion()) : null)
                        .deleteSuggestion(r.getDeleteSuggestion() != null ? copyDeleteSuggestion(r.getDeleteSuggestion()) : null)
                        .build())
                .collect(Collectors.toList());

        for (FormatSuggestionItem fmt : formatSuggestions) {
            Map<String, Object> fmtAttrs = parseAttrs(fmt.getAttributes());
            if (fmtAttrs.isEmpty()) continue;

            for (FormatSuggestionSpan span : fmt.getSpans()) {
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

                    if (run.getSuggestionAttributes() == null) {
                        run.setSuggestionAttributes(new LinkedHashMap<>());
                    }
                    // Only overlay into suggestionAttributes — baseAttributes untouched
                    run.getSuggestionAttributes().putAll(fmtAttrs);
                }
            }
        }

        return cloned;
    }

    // ─── buildVisualDelta ─────────────────────────────────────────────────────
    //
    // Converts the final runs array into a Quill Delta ready for updateContents().
    //
    // KEY CHANGE: Instead of emitting inserts for all runs, we emit RETAINS for
    // committed/deleted-committed runs (those without insertSuggestion). This prevents
    // suggestion attrs from bleeding into surrounding text when Quill applies inline
    // attribute inheritance.
    //
    // Run categories and their delta representation:
    //   - Committed, no suggestions       → retain(len) with base-attributes
    //   - Committed, format suggestion    → retain(len) with base-attributes +
    //                                       suggestion-attributes + suggestion-format
    //   - Committed, deleted              → insert("↵"/"text") with suggestion-delete(-newline)
    //                                       (no retain possible — text absent from committed doc)
    //   - Inserted pending run            → insert(text) with suggestion-insert +
    //                                       base-attributes + any suggestion-attributes
    //
    // Each op also carries "base-attributes" so the frontend can distinguish
    // the committed formatting from suggestion-applied formatting.
    //
    // Pass 1 — Collapse adjacent mergeable runs.
    // Pass 2 — Build the delta.
    // ─────────────────────────────────────────────────────────────────────────
    private Delta buildVisualDelta(List<ReviewRun> runs) {
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
                    run.getDeleteSuggestion() != null ? run.getDeleteSuggestion().getGroupId() : null)
                    && (last.getInsertSuggestion() == null) == (run.getInsertSuggestion() == null)
                    && attrsEq(
                    last.getBaseAttributes() != null ? last.getBaseAttributes() : Collections.emptyMap(),
                    run.getBaseAttributes() != null ? run.getBaseAttributes() : Collections.emptyMap());

            if (canMerge) {
                last.setText(last.getText() + run.getText());

                if (last.getInsertSuggestion() != null && run.getInsertSuggestion() != null) {
                    last.getInsertSuggestion().setReferences(mergeSuggestionSlices(
                            last.getInsertSuggestion().getReferences(),
                            run.getInsertSuggestion().getReferences()
                    ));
                }

                if (last.getDeleteSuggestion() != null && run.getDeleteSuggestion() != null) {
                    last.getDeleteSuggestion().setReferences(mergeSuggestionSlices(
                            last.getDeleteSuggestion().getReferences(),
                            run.getDeleteSuggestion().getReferences()
                    ));
                }
            } else {
                collapsed.add(ReviewRun.builder()
                        .text(run.getText())
                        .baseAttributes(new LinkedHashMap<>(run.getBaseAttributes() != null ? run.getBaseAttributes() : Collections.emptyMap()))
                        .suggestionAttributes(new LinkedHashMap<>(run.getSuggestionAttributes() != null ? run.getSuggestionAttributes() : Collections.emptyMap()))
                        .logicalStart(run.getLogicalStart())
                        .insertSuggestion(run.getInsertSuggestion() != null ? copyInsertSuggestion(run.getInsertSuggestion()) : null)
                        .deleteSuggestion(run.getDeleteSuggestion() != null ? copyDeleteSuggestion(run.getDeleteSuggestion()) : null)
                        .build());
            }
        }

        // ── Pass 2: Build retain-based overlay ──
        Delta delta = new Delta();

        for (ReviewRun run : collapsed) {
            Map<String, Object> baseAttrs = run.getBaseAttributes() != null
                    ? run.getBaseAttributes()
                    : Collections.emptyMap();
            Map<String, Object> suggestionAttrs = run.getSuggestionAttributes() != null
                    ? run.getSuggestionAttributes()
                    : Collections.emptyMap();

            boolean isInsertedPending = run.getInsertSuggestion() != null;
            boolean isDeletedPending = run.getDeleteSuggestion() != null && !isInsertedPending;
            boolean isDeletedNewline = "\n".equals(run.getText()) && run.getDeleteSuggestion() != null;

            Map<String, Object> attrs = new LinkedHashMap<>();

            if (!baseAttrs.isEmpty()) {
                attrs.put("base-attributes", new LinkedHashMap<>(baseAttrs));
            }

            if (!suggestionAttrs.isEmpty()) {
                attrs.put("suggestion-attributes", new LinkedHashMap<>(suggestionAttrs));
                attrs.putAll(suggestionAttrs);
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

                if (isDeletedNewline) {
                    attrs.put("suggestion-delete-newline", deletePayload);
                } else {
                    attrs.put("suggestion-delete", deletePayload);
                }
            }

            if (isDeletedPending) {
                // Still needed with your current base-doc contract:
                // deleted committed text is absent from `doc`, so it cannot be retained.
                String textToInsert = isDeletedNewline ? "↵" : run.getText();
                delta.insert(textToInsert, attrs.isEmpty() ? null : attrs);
            } else {
                // Committed runs and pending inserted runs are already present in `doc`
                int retainLen = run.getText().length();
                delta.retain(retainLen, attrs.isEmpty() ? null : attrs);
            }
        }

        return delta;
    }

    private void cancelInsert(
            String actorEmail,
            UUID noteId,
            List<SuggestionSlice> targetSlices,
            String deleteOpId,
            int deleteComponentIndex
    ) {
        if (targetSlices == null || targetSlices.isEmpty()) return;

        NoteDto note = redisService.getNote(noteId);
        List<TextOperation> logOps = note.revisionLog();

        int totalCancelledLength = 0;

        for (SuggestionSlice slice : cloneSuggestionSlices(targetSlices)) {
            if (slice.getLength() <= 0 || slice.getRef() == null) continue;

            OpReference ref = slice.getRef();

            TextOperation insertOp = logOps.stream()
                    .filter(op -> op.getOpId().equals(ref.opId()))
                    .findFirst()
                    .orElseThrow(() -> new BadRequestException("Insert op not found: " + ref.opId()));

            commitOrSplitInsertOp(
                    logOps,
                    insertOp,
                    ref.componentIndex(),
                    slice.getStart(),
                    slice.getLength()
            );

            totalCancelledLength += slice.getLength();
        }

        if (totalCancelledLength <= 0) return;

        TextOperation deleteOp = logOps.stream()
                .filter(op -> op.getOpId().equals(deleteOpId))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("Delete op not found: " + deleteOpId));

        commitOrSplitDeleteOp(
                logOps,
                deleteOp,
                deleteComponentIndex,
                0,
                totalCancelledLength
        );

        NoteVersionDto noteVersion = redisService.getNoteVersion(noteId);

        NoteDto updatedNote = new NoteDto(
                note.id(),
                note.ownerEmail(),
                note.title(),
                logOps,
                note.visibility(),
                note.accessRole(),
                note.currentNoteVersionNumber(),
                note.createdAt(),
                note.updatedAt()
        );

        redisService.updateNote(updatedNote, noteVersion);
        noteService.saveNote(actorEmail, noteId);
    }

    private void cancelFormat(String actorEmail, UUID noteId, CancelFormatPayload payload) {
        NoteDto note = redisService.getNote(noteId);
        List<TextOperation> logOps = note.revisionLog();

        TextOperation cancellingOp = logOps.stream()
                .filter(op -> op.getOpId().equals(payload.cancellingOpId()))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("Cancelling op not found: " + payload.cancellingOpId()));

        Op cancellingRetain = cancellingOp.getDelta().ops.get(payload.retainComponentIndex());
        if (cancellingRetain == null || !cancellingRetain.isRetain()) {
            throw new BadRequestException(
                    "Could not locate retain component at index "
                            + payload.retainComponentIndex()
                            + " for op: " + payload.cancellingOpId()
            );
        }

        int cancellingRetainTotal = (Integer) cancellingRetain.getRetain();
        int overlapLen = payload.opLength();

        for (OpReference ref : distinctRefs(payload.targetReferences())) {
            TextOperation targetOp = logOps.stream()
                    .filter(op -> op.getOpId().equals(ref.opId()))
                    .findFirst()
                    .orElse(null);

            if (targetOp == null || targetOp.getState() != OpState.PENDING) {
                continue;
            }

            Delta originalTargetDelta = targetOp.getDelta();
            Op targetRetain = originalTargetDelta.ops.get(ref.componentIndex());

            if (targetRetain == null) {
                throw new BadRequestException(
                        "Could not locate target retain component at index "
                                + ref.componentIndex()
                                + " for op: " + ref.opId()
                );
            }

            int fullLen = (Integer) targetRetain.getRetain();
            int consumed = Math.min(payload.consumedBefore() + overlapLen, fullLen);
            int remainingLen = fullLen - consumed;

            if (consumed <= 0) {
                continue;
            }

            if (remainingLen == 0) {
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
            }
        }

        int cancellingConsumed = payload.consumedBefore() + overlapLen;

        if (cancellingConsumed >= cancellingRetainTotal) {
            cancellingOp.setState(OpState.COMMITTED);
        } else {
            int cancellingRemainder = cancellingRetainTotal - cancellingConsumed;

            Delta committedDelta = new Delta();
            for (int i = 0; i < payload.retainComponentIndex(); i++) {
                committedDelta.push(cancellingOp.getDelta().ops.get(i));
            }
            committedDelta.retain(cancellingConsumed, cancellingRetain.getAttributes());

            TextOperation committedPart = new TextOperation(
                    committedDelta,
                    cancellingOp.getActorEmail(),
                    cancellingOp.getRevision(),
                    OpState.COMMITTED,
                    cancellingOp.getCreatedAt()
            );

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

            int cancellingIndex = logOps.indexOf(cancellingOp);
            logOps.add(cancellingIndex, committedPart);
        }

        NoteVersionDto noteVersion = redisService.getNoteVersion(noteId);

        NoteDto updatedNote = new NoteDto(
                note.id(),
                note.ownerEmail(),
                note.title(),
                logOps,
                note.visibility(),
                note.accessRole(),
                note.currentNoteVersionNumber(),
                note.createdAt(),
                note.updatedAt()
        );

        redisService.updateNote(updatedNote, noteVersion);
        noteService.saveNote(actorEmail, noteId);
    }
}