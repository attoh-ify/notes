package com.example.notes.services.impl;

import com.example.notes.dto.attribution.*;
import com.example.notes.dto.note.NoteDto;
import com.example.notes.dto.noteVersion.NoteVersionDto;
import com.example.notes.dto.ot.Delta;
import com.example.notes.dto.ot.Op;
import com.example.notes.dto.ot.TextOperation;
import com.example.notes.services.AttributionService;
import com.example.notes.services.RedisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

import static com.example.notes.utils.AttributionHelpers.*;

@Slf4j
@Service
public class AttributionServiceImpl implements AttributionService {
    private final RedisService redisService;
    private final NotePersistenceService notePersistenceService;

    public AttributionServiceImpl(
            RedisService redisService,
            NotePersistenceService notePersistenceService
    ) {
        this.redisService = redisService;
        this.notePersistenceService = notePersistenceService;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Logging helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static void logRuns(String label, List<ReviewRun> runs) {
        if (!log.isDebugEnabled()) return;
        log.debug("[ATTR:RUN] {} count={}", label, runs.size());
        for (int i = 0; i < runs.size(); i++) {
            ReviewRun r = runs.get(i);
            log.debug(
                    "[ATTR:RUN] {}  [{}] text='{}' logicalStart={} ins={} del={} baseAttrs={} suggAttrs={} refs={}",
                    label, i,
                    runTextForLog(r),
                    r.getLogicalStart(),
                    r.getInsertSuggestion() != null ? r.getInsertSuggestion().getGroupId() : "null",
                    r.getDeleteSuggestion() != null ? r.getDeleteSuggestion().getGroupId() : "null",
                    r.getBaseAttributes(),
                    r.getSuggestionAttributes(),
                    r.getReferences()
            );
        }
    }

    private static void logFormatSuggestions(String label, List<FormatSuggestionItem> fmts) {
        if (!log.isDebugEnabled()) return;
        log.debug("[ATTR:FMT] {} count={}", label, fmts.size());
        for (FormatSuggestionItem f : fmts) {
            log.debug(
                    "[ATTR:FMT] {}  groupId={} actor={} key={} value={} refs={} depsIns={} depsDel={}",
                    label,
                    f.getGroupId(), f.getActorEmail(),
                    f.getAttributeKey(), f.getAttributeValue(),
                    f.getReferences(), f.getDependsOnInsertGroupIds(),
                    f.getDependsOnDeleteGroupIds()
            );
        }
    }

    private static void logVisualDelta(String label, Delta delta) {
        if (!log.isDebugEnabled()) return;
        log.debug("[ATTR:VDELTA] {} ops={}", label, delta.ops);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main method
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public ReviewProjection buildReviewProjection(
            String actorEmail,
            UUID noteId,
            List<TextOperation> baseTextOps,
            List<TextOperation> changeTextOps
    ) {
        log.info("[ATTR:PHASE] buildReviewProjection START noteId={} actor={} baseOps={} changeOps={}",
                noteId, actorEmail, baseTextOps.size(), changeTextOps.size());

        resetGroupCounter();

        // ── PHASE 1 ───────────────────────────────────────────────────────────
        log.info("[ATTR:PHASE] PHASE-1: composing base delta from {} ops", baseTextOps.size());
        Delta baseDelta = new Delta();

        for (TextOperation textOp : baseTextOps) {
            log.debug("[ATTR:PHASE1] composing opId={} delta={}", textOp.getOpId(), textOp.getDelta());
            baseDelta = baseDelta.compose(new Delta(textOp.getDelta().ops));
        }

        log.info("[ATTR:PHASE1] baseDelta composed: ops={}", baseDelta.ops.size());
        log.debug("[ATTR:PHASE1] baseDelta={}", baseDelta);

        // ── PHASE 2 ───────────────────────────────────────────────────────────
        log.info("[ATTR:PHASE] PHASE-2: building ReviewRun list from baseDelta");
        List<ReviewRun> runs = new ArrayList<>();
        int seedPos = 0;

        for (Op op : baseDelta.ops) {
            Map<String, Object> opAttrs = op.getAttributes() != null
                    ? new LinkedHashMap<>(op.getAttributes())
                    : new LinkedHashMap<>();

            if (op.getInsert() instanceof String insertStr) {
                String[] parts = insertStr.split("\n", -1);

                for (int i = 0; i < parts.length; i++) {
                    if (!parts[i].isEmpty()) {
                        runs.add(
                                ReviewRun.builder()
                                        .text(parts[i])
                                        .baseAttributes(new LinkedHashMap<>(opAttrs))
                                        .suggestionAttributes(new LinkedHashMap<>())
                                        .references(new ArrayList<>())
                                        .logicalStart(seedPos)
                                        .build()
                        );
                        seedPos += parts[i].length();
                    }

                    if (i < parts.length - 1) {
                        runs.add(
                                ReviewRun.builder()
                                        .text("\n")
                                        .baseAttributes(new LinkedHashMap<>())
                                        .suggestionAttributes(new LinkedHashMap<>())
                                        .references(new ArrayList<>())
                                        .logicalStart(seedPos)
                                        .build()
                        );
                        seedPos += 1;
                    }
                }

                continue;
            }

            if (op.getInsert() instanceof Map<?, ?> embed) {
                runs.add(
                        ReviewRun.builder()
                                .embed(cloneEmbed(embed))
                                .baseAttributes(new LinkedHashMap<>(opAttrs))
                                .suggestionAttributes(new LinkedHashMap<>())
                                .references(new ArrayList<>())
                                .logicalStart(seedPos)
                                .build()
                );

                seedPos += 1;
            }
        }

        log.info("[ATTR:PHASE2] runs built: count={} totalLogicalLen={}", runs.size(), seedPos);
        logRuns("AFTER_PHASE2", runs);

        // ── PHASE 3 ───────────────────────────────────────────────────────────
        log.info("[ATTR:PHASE] PHASE-3: replaying {} change ops", changeTextOps.size());

        List<FormatSuggestionItem> formatSuggestions = new ArrayList<>();
        ReviewOperationAccumulator accumulator = new ReviewOperationAccumulator();

        for (TextOperation textOp : changeTextOps) {
            String opId = textOp.getOpId();
            String authorEmail = textOp.getActorEmail();
            String createdAt = textOp.getCreatedAt().toString();

            log.info("[ATTR:OP] processing opId={} actor={} createdAt={} components={}",
                    opId, authorEmail, createdAt, textOp.getDelta().ops.size());
            log.debug("[ATTR:OP] delta={}", textOp.getDelta());

            int localLogPos = 0;
            InsertSuggestion currentInsertGroup = null;
            DeleteSuggestion currentDeleteGroup = null;
            FormatSuggestionItem currentFormatGroup = null;

            List<Op> components = textOp.getDelta().ops;

            for (int compIdx = 0; compIdx < components.size(); compIdx++) {
                Op component = components.get(compIdx);
                log.debug("[ATTR:COMP] opId={} compIdx={} localLogPos={} component={}",
                        opId, compIdx, localLogPos, component);

                // ── CASE A: plain retain ──────────────────────────────────────
                if (component.isRetain() && component.getAttributes() == null) {
                    boolean isLastOp = compIdx == components.size() - 1;

                    if (isLastOp) {
                        log.debug("[ATTR:RETAIN] opId={} compIdx={} LAST_OP skipping", opId, compIdx);
                        break;
                    }

                    currentInsertGroup = null;
                    currentDeleteGroup = null;

                    int retainLen = (int) component.getRetain();
                    boolean newlineOnly = isOnlyNewlineRetain(runs, localLogPos, retainLen);

                    log.debug("[ATTR:RETAIN] opId={} compIdx={} retainLen={} newlineOnly={} localLogPos={}",
                            opId, compIdx, retainLen, newlineOnly, localLogPos);

                    if (!newlineOnly) {
                        if (currentFormatGroup != null) {
                            log.debug("[ATTR:RETAIN] breaking formatGroup groupId={} due to non-newline retain",
                                    currentFormatGroup.getGroupId());
                        }
                        currentFormatGroup = null;
                    }

                    localLogPos += retainLen;
                    continue;
                }

                // ── CASE B: retain with attributes (format suggestion) ────────
                else if (component.isRetain() && component.getAttributes() != null) {
                    currentInsertGroup = null;
                    currentDeleteGroup = null;

                    int retainLen = (int) component.getRetain();
                    log.info("[ATTR:FORMAT] opId={} compIdx={} retainLen={} attrs={} localLogPos={}",
                            opId, compIdx, retainLen, component.getAttributes(), localLogPos);

                    RunPosition startPos = findRunPos(runs, localLogPos);
                    int runIdx = startPos.idx();
                    int startOffset = startPos.offset();

                    log.debug("[ATTR:FORMAT] findRunPos(localLogPos={}) => idx={} offset={} absPos={}",
                            localLogPos, runIdx, startOffset, startPos.absPos());

                    if (runIdx >= runs.size() && retainLen > 0) {
                        log.error("[ATTR:FORMAT:ERR] opId={} compIdx={} runIdx={} >= runs.size()={} — format target out of bounds, localLogPos={}",
                                opId, compIdx, runIdx, runs.size(), localLogPos);
                    }

                    if (startOffset > 0 && runIdx < runs.size()) {
                        log.debug("[ATTR:FORMAT] splitting run at idx={} offset={}", runIdx, startOffset);
                        runIdx = splitAt(runs, runIdx, startOffset);
                        log.debug("[ATTR:FORMAT] after split runIdx={}", runIdx);
                    }

                    int remaining = retainLen;
                    int cursor = runIdx;
                    Map<String, Object> rawIncomingAttrs = new LinkedHashMap<>(component.getAttributes());

                    while (remaining > 0 && cursor < runs.size()) {
                        ReviewRun run = runs.get(cursor);

                        if (run.getDeleteSuggestion() != null || "\n".equals(run.getText())) {
                            log.debug("[ATTR:FORMAT] skipping cursor={} del={} isNL={}",
                                    cursor,
                                    run.getDeleteSuggestion() != null,
                                    "\n".equals(run.getText()));
                            cursor++;
                            continue;
                        }

                        if (run.isText() && run.length() > remaining) {
                            splitAt(runs, cursor, remaining);
                        }

                        ReviewRun target = runs.get(cursor);
                        int spanStart = target.getLogicalStart();
                        int spanLen = target.length();
                        int spanEnd = spanStart + spanLen;

                        log.debug("[ATTR:FORMAT] target cursor={} text='{}' spanStart={} spanLen={} spanEnd={}",
                                cursor, runTextForLog(target), spanStart, spanLen, spanEnd);

                        Map<String, Object> baseAttrs = target.getBaseAttributes() != null
                                ? target.getBaseAttributes()
                                : Collections.emptyMap();

                        for (Map.Entry<String, Object> entry : rawIncomingAttrs.entrySet()) {
                            String attrKey = entry.getKey();
                            Object attrValue = entry.getValue();
                            Object baseValue = baseAttrs.get(attrKey);

                            log.debug("[ATTR:FORMAT] attrKey={} attrValue={} baseValue={}", attrKey, attrValue, baseValue);

                            List<FormatSuggestionItem> coveringFormats = formatSuggestions.stream()
                                    .filter(f -> attrKey.equals(f.getAttributeKey()))
                                    .filter(f -> formatSuggestionCoversRange(f, spanStart, spanLen))
                                    .toList();

                            if (!coveringFormats.isEmpty()) {
                                log.debug("[ATTR:FORMAT] coveringFormats count={} for spanStart={} spanLen={}",
                                        coveringFormats.size(), spanStart, spanLen);
                            }

                            for (FormatSuggestionItem fmt : new ArrayList<>(coveringFormats)) {
                                FormatKeyChangeType type = getFormatKeyChangeType(fmt, attrValue, baseValue);
                                log.debug("[ATTR:FORMAT] coveringFmt groupId={} changeType={}", fmt.getGroupId(), type);

                                if (type == FormatKeyChangeType.CANCEL || type == FormatKeyChangeType.REPLACE) {
                                    for (Reference reference : fmt.getReferences()) {
                                        int referenceStart = reference.getReviewStart();
                                        int referenceEnd = referenceStart + reference.getLength();
                                        int overlapStart = Math.max(spanStart, referenceStart);
                                        int overlapEnd = Math.min(spanEnd, referenceEnd);

                                        if (overlapStart >= overlapEnd) continue;

                                        int sourceStart = reference.getComponentStart() + (overlapStart - referenceStart);
                                        int sourceLen = overlapEnd - overlapStart;

                                        log.info("[ATTR:FORMAT:CANCEL] opId={} compIdx={} cancelFmt groupId={} attrKey={} sourceStart={} sourceLen={}",
                                                opId, compIdx, fmt.getGroupId(), attrKey, sourceStart, sourceLen);

                                        accumulator.recordFormatCancellation(
                                                reference.getOpId(),
                                                reference.getComponentIndex(),
                                                attrKey,
                                                sourceStart,
                                                sourceLen
                                        );
                                    }

                                    fmt.setReferences(
                                            removeRangeFromSuggestionReferencesWithoutShift(
                                                    fmt.getReferences(), spanStart, spanLen)
                                    );

                                    if (fmt.getReferences().isEmpty()) {
                                        log.debug("[ATTR:FORMAT] removing empty formatSuggestion groupId={}", fmt.getGroupId());
                                        formatSuggestions.remove(fmt);
                                    }

                                    if (target.getSuggestionAttributes() != null) {
                                        target.getSuggestionAttributes().remove(attrKey);
                                    }
                                }
                            }

                            if (Objects.equals(baseValue, attrValue)) {
                                log.debug("[ATTR:FORMAT] attrKey={} already matches base — no suggestion", attrKey);
                                continue;
                            }

                            Map<String, Object> incomingSuggestionAttrs = new LinkedHashMap<>();
                            incomingSuggestionAttrs.put(attrKey, attrValue);

                            target.setSuggestionAttributes(
                                    overlayAttrsPreserveNull(
                                            target.getSuggestionAttributes(),
                                            incomingSuggestionAttrs
                                    )
                            );

                            if (currentFormatGroup == null
                                    || !attrKey.equals(currentFormatGroup.getAttributeKey())
                                    || !Objects.equals(attrValue, currentFormatGroup.getAttributeValue())) {

                                currentFormatGroup = findOrCreateCompatibleFormatSuggestion(
                                        formatSuggestions, authorEmail, createdAt,
                                        attrKey, attrValue, spanStart, spanEnd
                                );
                                log.debug("[ATTR:FORMAT] found/created formatGroup groupId={}", currentFormatGroup.getGroupId());
                            }

                            if (target.getInsertSuggestion() != null
                                    && !currentFormatGroup.getDependsOnInsertGroupIds()
                                    .contains(target.getInsertSuggestion().getGroupId())) {
                                log.debug("[ATTR:FORMAT] adding insertDep groupId={} -> insGroupId={}",
                                        currentFormatGroup.getGroupId(),
                                        target.getInsertSuggestion().getGroupId());
                                currentFormatGroup.getDependsOnInsertGroupIds()
                                        .add(target.getInsertSuggestion().getGroupId());
                            }

                            int componentStart = retainLen - remaining;

                            log.debug("[ATTR:FORMAT] addRef groupId={} reviewStart={} componentStart={} len={} opId={} compIdx={}",
                                    currentFormatGroup.getGroupId(), spanStart, componentStart, spanLen, opId, compIdx);

                            currentFormatGroup.setReferences(
                                    addSuggestionReference(
                                            currentFormatGroup.getReferences(),
                                            spanStart, componentStart, spanLen, opId, compIdx
                                    )
                            );
                        }

                        remaining -= spanLen;
                        cursor++;
                    }

                    if (remaining != 0) {
                        log.warn("[ATTR:FORMAT:WARN] opId={} compIdx={} retainLen={} — remaining={} after loop (expected 0)",
                                opId, compIdx, retainLen, remaining);
                    }

                    localLogPos += retainLen;
                    continue;
                }

                // ── CASE C: insert ────────────────────────────────────────────
                else if (component.isInsert()) {
                    currentDeleteGroup = null;
                    currentFormatGroup = null;

                    List<InsertFragment> fragments = buildInsertFragments(component.getInsert());

                    if (fragments.isEmpty()) {
                        log.warn("[ATTR:INSERT:WARN] opId={} compIdx={} unsupported insert value={}",
                                opId, compIdx, component.getInsert());
                        continue;
                    }

                    InsertContentKind insertKind = kindOfFragment(fragments.get(0));

                    int shiftLen = fragments.stream()
                            .mapToInt(InsertFragment::length)
                            .sum();

                    log.info("[ATTR:INSERT] opId={} compIdx={} insertValue={} shiftLen={} localLogPos={} attrs={}",
                            opId, compIdx, component.getInsert(), shiftLen, localLogPos, component.getAttributes());

                    RunPosition insertPos = findRunPos(runs, localLogPos);
                    int runIndex = insertPos.idx();
                    int insertOffset = insertPos.offset();
                    int insertAbsPos = insertPos.absPos();

                    log.debug("[ATTR:INSERT] findRunPos(localLogPos={}) => idx={} offset={} absPos={}",
                            localLogPos, runIndex, insertOffset, insertAbsPos);

                    int insertAtIdx = runIndex;

                    if (insertOffset > 0 && runIndex < runs.size()) {
                        log.debug("[ATTR:INSERT] splitting run at idx={} offset={}", runIndex, insertOffset);
                        insertAtIdx = splitAt(runs, runIndex, insertOffset);
                        log.debug("[ATTR:INSERT] after split insertAtIdx={}", insertAtIdx);
                    }

                    ReviewRun prevRun = insertAtIdx > 0 ? runs.get(insertAtIdx - 1) : null;
                    ReviewRun nextRun = insertAtIdx < runs.size() ? runs.get(insertAtIdx) : null;

                    log.debug("[ATTR:INSERT] prevRun={} nextRun={}",
                            prevRun != null
                                    ? "run='" + runTextForLog(prevRun) + "' ins=" + (prevRun.getInsertSuggestion() != null ? prevRun.getInsertSuggestion().getGroupId() : "null")
                                    : "null",
                            nextRun != null
                                    ? "run='" + runTextForLog(nextRun) + "' ins=" + (nextRun.getInsertSuggestion() != null ? nextRun.getInsertSuggestion().getGroupId() : "null")
                                    : "null"
                    );

                    if (currentInsertGroup == null) {
                        InsertSuggestion prevAdj =
                                compatibleAdjacentInsertSuggestion(prevRun, authorEmail, insertKind);

                        InsertSuggestion nextAdj =
                                compatibleAdjacentInsertSuggestion(nextRun, authorEmail, insertKind);

                        InsertSuggestion adj = prevAdj != null ? prevAdj : nextAdj;

                        currentInsertGroup = adj != null
                                ? copyInsertSuggestion(adj)
                                : InsertSuggestion.builder()
                                .groupId(nextId())
                                .actorEmail(authorEmail)
                                .createdAt(createdAt)
                                .build();

                        log.debug("[ATTR:INSERT] insertGroup resolved: groupId={} adj={} kind={}",
                                currentInsertGroup.getGroupId(), adj != null, insertKind);

                    } else {
                        boolean currentGroupStillCompatible =
                                (prevRun != null
                                        && prevRun.getInsertSuggestion() != null
                                        && currentInsertGroup.getGroupId().equals(prevRun.getInsertSuggestion().getGroupId())
                                        && sameInsertContentKind(prevRun, insertKind))
                                        ||
                                        (nextRun != null
                                                && nextRun.getInsertSuggestion() != null
                                                && currentInsertGroup.getGroupId().equals(nextRun.getInsertSuggestion().getGroupId())
                                                && sameInsertContentKind(nextRun, insertKind));

                        if (!currentGroupStillCompatible) {
                            currentInsertGroup = InsertSuggestion.builder()
                                    .groupId(nextId())
                                    .actorEmail(authorEmail)
                                    .createdAt(createdAt)
                                    .build();

                            log.debug("[ATTR:INSERT] new insertGroup because content kind changed; groupId={} kind={}",
                                    currentInsertGroup.getGroupId(), insertKind);

                        } else if (createdAt.compareTo(currentInsertGroup.getCreatedAt()) > 0) {
                            log.debug("[ATTR:INSERT] updating createdAt on insertGroup groupId={}",
                                    currentInsertGroup.getGroupId());
                            currentInsertGroup.setCreatedAt(createdAt);
                        }
                    }

                    Map<String, Object> ownAttrs = component.getAttributes() != null
                            ? new LinkedHashMap<>(component.getAttributes())
                            : new LinkedHashMap<>();

                    Map<String, Object> inheritedSuggestionAttrs = new LinkedHashMap<>();
                    Set<String> extendedGroupIds = new LinkedHashSet<>();

                    for (Iterator<Map.Entry<String, Object>> it = ownAttrs.entrySet().iterator(); it.hasNext(); ) {
                        Map.Entry<String, Object> entry = it.next();
                        String key = entry.getKey();
                        Object value = entry.getValue();
                        int finalLocalLogPos = localLogPos;

                        FormatSuggestionItem inheritedFormat = formatSuggestions.stream()
                                .filter(f -> key.equals(f.getAttributeKey()))
                                .filter(f -> Objects.equals(value, f.getAttributeValue()))
                                .filter(f -> formatSuggestionShouldInheritInsert(f, finalLocalLogPos))
                                .findFirst()
                                .orElse(null);

                        if (inheritedFormat == null) continue;

                        log.info("[ATTR:INSERT:INHERIT] opId={} compIdx={} key={} value={} inheritedFromGroup={}",
                                opId, compIdx, key, value, inheritedFormat.getGroupId());

                        extendFormatGroupForInheritedInsert(
                                inheritedFormat,
                                localLogPos,
                                shiftLen,
                                opId,
                                compIdx,
                                currentInsertGroup.getGroupId()
                        );

                        inheritedSuggestionAttrs.put(key, value);
                        extendedGroupIds.add(inheritedFormat.getGroupId());
                        it.remove();
                    }

                    Map<String, Object> prevEffectiveAttrs = getEffectiveAttrs(prevRun);
                    Map<String, Object> nextEffectiveAttrs = getEffectiveAttrs(nextRun);

                    if (!ownAttrs.isEmpty()
                            && prevRun != null
                            && prevRun.getInsertSuggestion() != null
                            && !authorEmail.equals(prevRun.getInsertSuggestion().getActorEmail())
                            && !prevEffectiveAttrs.isEmpty()) {

                        Map<String, Object> inherited = intersectAttrs(ownAttrs, prevEffectiveAttrs);

                        if (!inherited.isEmpty()) {
                            log.info("[ATTR:INSERT:CROSS-INHERIT-PREV] opId={} compIdx={} inherited={}",
                                    opId, compIdx, inherited);

                            InsertGroupCollection prevGroup = collectInsertGroupRunsWithAttrs(
                                    runs,
                                    prevRun.getInsertSuggestion().getGroupId(),
                                    inherited
                            );

                            if (prevGroup != null) {
                                log.debug("[ATTR:INSERT:CROSS-INHERIT-PREV] prevGroup indices={} start={} end={}",
                                        prevGroup.indices(), prevGroup.start(), prevGroup.end());

                                for (Map.Entry<String, Object> inheritedEntry : inherited.entrySet()) {
                                    String inheritedKey = inheritedEntry.getKey();
                                    Object inheritedValue = inheritedEntry.getValue();

                                    Map<String, Object> singleInherited =
                                            new LinkedHashMap<>(Map.of(inheritedKey, inheritedValue));

                                    FormatSuggestionItem g = findOrCreateFormatSuggestionByIdentity(
                                            formatSuggestions,
                                            prevRun.getInsertSuggestion().getActorEmail(),
                                            prevRun.getInsertSuggestion().getCreatedAt(),
                                            inheritedKey,
                                            inheritedValue
                                    );

                                    List<Reference> prevRefs = collectReferencesForRunIndices(
                                            runs,
                                            prevGroup.indices()
                                    );

                                    for (Reference ref : prevRefs) {
                                        boolean alreadyExists = g.getReferences().stream().anyMatch(existing ->
                                                existing.getReviewStart() == ref.getReviewStart()
                                                        && existing.getComponentStart() == ref.getComponentStart()
                                                        && existing.getLength() == ref.getLength()
                                                        && Objects.equals(existing.getOpId(), ref.getOpId())
                                                        && Objects.equals(existing.getComponentIndex(), ref.getComponentIndex())
                                        );

                                        if (!alreadyExists) {
                                            g.setReferences(appendAndCoalesceSuggestionReference(g.getReferences(), ref));
                                        }
                                    }

                                    g.setReferences(addSuggestionReference(
                                            g.getReferences(),
                                            localLogPos,
                                            0,
                                            shiftLen,
                                            opId,
                                            compIdx
                                    ));

                                    addInsertDependency(g, prevRun.getInsertSuggestion().getGroupId());
                                    addInsertDependency(g, currentInsertGroup.getGroupId());
                                    extendedGroupIds.add(g.getGroupId());

                                    moveAttrsFromBaseToSuggestionForRuns(
                                            runs,
                                            prevGroup.indices(),
                                            singleInherited
                                    );

                                    inheritedSuggestionAttrs.put(inheritedKey, inheritedValue);
                                }

                                ownAttrs = subtractAttrs(ownAttrs, inherited);
                            } else {
                                log.warn("[ATTR:INSERT:CROSS-INHERIT-PREV] prevGroup is null for groupId={}",
                                        prevRun.getInsertSuggestion().getGroupId());
                            }
                        }
                    }

                    if (!ownAttrs.isEmpty()
                            && nextRun != null
                            && nextRun.getInsertSuggestion() != null
                            && !authorEmail.equals(nextRun.getInsertSuggestion().getActorEmail())
                            && !nextEffectiveAttrs.isEmpty()) {

                        Map<String, Object> inherited = intersectAttrs(ownAttrs, nextEffectiveAttrs);

                        if (!inherited.isEmpty()) {
                            log.info("[ATTR:INSERT:CROSS-INHERIT-NEXT] opId={} compIdx={} inherited={}",
                                    opId, compIdx, inherited);

                            InsertGroupCollection nextGroup = collectInsertGroupRunsWithAttrs(
                                    runs,
                                    nextRun.getInsertSuggestion().getGroupId(),
                                    inherited
                            );

                            if (nextGroup != null) {
                                log.debug("[ATTR:INSERT:CROSS-INHERIT-NEXT] nextGroup indices={} start={} end={}",
                                        nextGroup.indices(), nextGroup.start(), nextGroup.end());

                                for (Map.Entry<String, Object> inheritedEntry : inherited.entrySet()) {
                                    String inheritedKey = inheritedEntry.getKey();
                                    Object inheritedValue = inheritedEntry.getValue();

                                    Map<String, Object> singleInherited =
                                            new LinkedHashMap<>(Map.of(inheritedKey, inheritedValue));

                                    FormatSuggestionItem g = findOrCreateFormatSuggestionByIdentity(
                                            formatSuggestions,
                                            nextRun.getInsertSuggestion().getActorEmail(),
                                            nextRun.getInsertSuggestion().getCreatedAt(),
                                            inheritedKey,
                                            inheritedValue
                                    );

                                    List<Reference> nextRefs = collectReferencesForRunIndices(
                                            runs,
                                            nextGroup.indices()
                                    );

                                    for (Reference ref : nextRefs) {
                                        boolean alreadyExists = g.getReferences().stream().anyMatch(existing ->
                                                existing.getReviewStart() == ref.getReviewStart()
                                                        && existing.getComponentStart() == ref.getComponentStart()
                                                        && existing.getLength() == ref.getLength()
                                                        && Objects.equals(existing.getOpId(), ref.getOpId())
                                                        && Objects.equals(existing.getComponentIndex(), ref.getComponentIndex())
                                        );

                                        if (!alreadyExists) {
                                            g.setReferences(appendAndCoalesceSuggestionReference(g.getReferences(), ref));
                                        }
                                    }

                                    g.setReferences(addSuggestionReference(
                                            g.getReferences(),
                                            localLogPos,
                                            0,
                                            shiftLen,
                                            opId,
                                            compIdx
                                    ));

                                    addInsertDependency(g, nextRun.getInsertSuggestion().getGroupId());
                                    addInsertDependency(g, currentInsertGroup.getGroupId());
                                    extendedGroupIds.add(g.getGroupId());

                                    moveAttrsFromBaseToSuggestionForRuns(
                                            runs,
                                            nextGroup.indices(),
                                            singleInherited
                                    );

                                    inheritedSuggestionAttrs.put(inheritedKey, inheritedValue);
                                }

                                ownAttrs = subtractAttrs(ownAttrs, inherited);
                            } else {
                                log.warn("[ATTR:INSERT:CROSS-INHERIT-NEXT] nextGroup is null for groupId={}",
                                        nextRun.getInsertSuggestion().getGroupId());
                            }
                        }
                    }

                    log.debug("[ATTR:INSERT] shifting refs: insertAbsPos={} shiftLen={} excludeGroup={}",
                            insertAbsPos, shiftLen, currentInsertGroup.getGroupId());

                    shiftSuggestionReferenceReviewStarts(
                            runs,
                            insertAbsPos,
                            shiftLen,
                            currentInsertGroup.getGroupId()
                    );

                    shiftFormatSuggestionReferences(
                            formatSuggestions,
                            insertAbsPos,
                            shiftLen,
                            extendedGroupIds
                    );

                    int spliceAt = insertAtIdx;
                    int runPos = insertAbsPos;

                    log.debug("[ATTR:INSERT] building {} fragment run(s)", fragments.size());

                    for (InsertFragment fragment : fragments) {
                        InsertSuggestion runSuggestion = copyInsertSuggestion(currentInsertGroup);

                        List<Reference> runRefs = addSuggestionReference(
                                new ArrayList<>(),
                                runPos,
                                fragment.componentStart(),
                                fragment.length(),
                                opId,
                                compIdx
                        );

                        ReviewRun.ReviewRunBuilder builder = ReviewRun.builder()
                                .baseAttributes(fragment.newline()
                                        ? new LinkedHashMap<>()
                                        : new LinkedHashMap<>(ownAttrs))
                                .suggestionAttributes(fragment.newline()
                                        ? new LinkedHashMap<>()
                                        : new LinkedHashMap<>(inheritedSuggestionAttrs))
                                .references(runRefs)
                                .logicalStart(runPos)
                                .insertSuggestion(runSuggestion);

                        if (fragment.isEmbed()) {
                            builder.embed(fragment.embed());
                        } else {
                            builder.text(fragment.text());
                        }

                        ReviewRun newRun = builder.build();

                        log.debug("[ATTR:INSERT] inserting run at spliceAt={} content='{}' logicalStart={} groupId={}",
                                spliceAt,
                                runTextForLog(newRun),
                                runPos,
                                currentInsertGroup.getGroupId());

                        runs.add(spliceAt++, newRun);
                        runPos += fragment.length();
                    }

                    for (int i = spliceAt; i < runs.size(); i++) {
                        runs.get(i).setLogicalStart(runs.get(i).getLogicalStart() + shiftLen);
                    }

                    log.debug("[ATTR:INSERT] shifted {} trailing runs forward by {}",
                            runs.size() - spliceAt, shiftLen);

                    log.debug("[ATTR:INSERT] localLogPos {} -> {}",
                            localLogPos, localLogPos + shiftLen);

                    localLogPos += shiftLen;
                    continue;
                }

                // ── CASE D: delete ────────────────────────────────────────────
                else if (component.isDelete()) {
                    currentInsertGroup = null;
                    currentFormatGroup = null;

                    log.info("[ATTR:DELETE] opId={} compIdx={} deleteLen={} localLogPos={}",
                            opId, compIdx, component.getDelete(), localLogPos);

                    RunPosition deletePos = findRunPos(runs, localLogPos);
                    int ri = deletePos.idx();
                    int deleteOffset = deletePos.offset();
                    int cursor = ri;

                    log.debug("[ATTR:DELETE] findRunPos(localLogPos={}) => idx={} offset={} absPos={}",
                            localLogPos, ri, deleteOffset, deletePos.absPos());

                    if (ri >= runs.size() && component.getDelete() > 0) {
                        log.error("[ATTR:DELETE:ERR] opId={} compIdx={} ri={} >= runs.size()={} — delete target out of bounds",
                                opId, compIdx, ri, runs.size());
                    }

                    if (deleteOffset > 0 && ri < runs.size()) {
                        log.debug("[ATTR:DELETE] splitting run at idx={} offset={}", ri, deleteOffset);
                        cursor = splitAt(runs, ri, deleteOffset);
                        log.debug("[ATTR:DELETE] after split cursor={}", cursor);
                    }

                    if (currentDeleteGroup == null) {
                        ReviewRun prevRunD = cursor > 0 ? runs.get(cursor - 1) : null;
                        ReviewRun nextRunD = cursor < runs.size() ? runs.get(cursor) : null;

                        DeleteSuggestion prevAdj =
                                prevRunD != null
                                        && prevRunD.getDeleteSuggestion() != null
                                        && authorEmail.equals(prevRunD.getDeleteSuggestion().getActorEmail())
                                        ? prevRunD.getDeleteSuggestion() : null;

                        DeleteSuggestion nextAdj =
                                nextRunD != null
                                        && nextRunD.getDeleteSuggestion() != null
                                        && authorEmail.equals(nextRunD.getDeleteSuggestion().getActorEmail())
                                        ? nextRunD.getDeleteSuggestion() : null;

                        if (prevAdj != null) {
                            currentDeleteGroup = copyDeleteSuggestion(prevAdj);
                            log.debug("[ATTR:DELETE] reusing prev deleteGroup groupId={}", currentDeleteGroup.getGroupId());

                            if (nextAdj != null && !nextAdj.getGroupId().equals(prevAdj.getGroupId())) {
                                log.debug("[ATTR:DELETE] merging nextAdj groupId={} into prevAdj groupId={}",
                                        nextAdj.getGroupId(), prevAdj.getGroupId());

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
                            log.debug("[ATTR:DELETE] reusing next deleteGroup groupId={}", currentDeleteGroup.getGroupId());
                        } else {
                            currentDeleteGroup = DeleteSuggestion.builder()
                                    .groupId(nextId())
                                    .actorEmail(authorEmail)
                                    .createdAt(createdAt)
                                    .build();
                            log.debug("[ATTR:DELETE] new deleteGroup groupId={}", currentDeleteGroup.getGroupId());
                        }
                    } else if (createdAt.compareTo(currentDeleteGroup.getCreatedAt()) > 0) {
                        currentDeleteGroup.setCreatedAt(createdAt);
                    }

                    int remaining = component.getDelete();
                    int deleteComponentLength = component.getDelete();

                    while (remaining > 0 && cursor < runs.size()) {
                        ReviewRun run = runs.get(cursor);
                        int deleteComponentLocalStart = deleteComponentLength - remaining;

                        if (run.getDeleteSuggestion() != null) {
                            log.debug("[ATTR:DELETE] cursor={} already deleted, skipping", cursor);
                            cursor++;
                            continue;
                        }

                        if ("\n".equals(run.getText()) && run.getInsertSuggestion() == null) {
                            log.debug("[ATTR:DELETE] deleting base newline at cursor={} logicalStart={}", cursor, run.getLogicalStart());

                            DeleteSuggestion.DeleteSuggestionType nextType =
                                    promotedDeleteType(currentDeleteGroup.getType(), true);

                            currentDeleteGroup.setType(nextType);
                            applyDeleteTypeToGroupRuns(runs, currentDeleteGroup.getGroupId(), nextType);

                            DeleteSuggestion newlineDelete = copyDeleteSuggestion(currentDeleteGroup);

                            run.setReferences(addSuggestionReference(
                                    new ArrayList<>(), run.getLogicalStart(), deleteComponentLocalStart, 1, opId, compIdx));
                            run.setDeleteSuggestion(newlineDelete);

                            remaining--;
                            localLogPos++;
                            cursor++;
                            continue;
                        }

                        if (run.isText() && remaining < run.length()) {
                            splitAt(runs, cursor, remaining);
                        }

                        ReviewRun target = runs.get(cursor);
                        int len = target.length();

                        if (target.getInsertSuggestion() != null) {
                            int runStart = target.getLogicalStart();
                            int runEnd = runStart + target.length();
                            List<Reference> targetReferences = target.getReferences();
                            int deleteStartPos = target.getLogicalStart();
                            int deleteLen = target.length();
                            int deleteEndPos = deleteStartPos + deleteLen;

                            log.info("[ATTR:DELETE:CANCEL-INSERT] opId={} compIdx={} cancelInsert groupId={} runStart={} runEnd={} deleteStartPos={} deleteLen={}",
                                    opId, compIdx,
                                    target.getInsertSuggestion().getGroupId(),
                                    runStart, runEnd, deleteStartPos, deleteLen);

                            runs.remove(cursor);

                            for (int i = cursor; i < runs.size(); i++) {
                                runs.get(i).setLogicalStart(runs.get(i).getLogicalStart() - deleteLen);
                            }

                            deleteRangeFromRunReferencesAndShift(runs, deleteStartPos, deleteLen);
                            deleteRangeFromFormatSuggestionReferencesAndShift(formatSuggestions, deleteStartPos, deleteLen);

                            formatSuggestions.removeIf(fmt -> {
                                boolean empty = fmt.getReferences() == null || fmt.getReferences().isEmpty();
                                if (empty) log.debug("[ATTR:DELETE:CANCEL-INSERT] removing empty formatSuggestion groupId={}", fmt.getGroupId());
                                return empty;
                            });

                            for (Reference reference : targetReferences) {
                                int referenceStart = reference.getReviewStart();
                                int referenceEnd = referenceStart + reference.getLength();

                                if (referenceStart < runStart || referenceEnd > runEnd) {
                                    int overlapStart = Math.max(runStart, referenceStart);
                                    int overlapEnd = Math.min(runEnd, referenceEnd);
                                    if (overlapStart >= overlapEnd) continue;

                                    int insertCancelCompStart = reference.getComponentStart() + (overlapStart - referenceStart);
                                    int insertCancelLen = overlapEnd - overlapStart;
                                    int deleteCancelCompStart = deleteComponentLocalStart + (overlapStart - runStart);

                                    accumulator.recordInsertCancellation(
                                            reference.getOpId(), reference.getComponentIndex(),
                                            insertCancelCompStart, insertCancelLen);
                                    accumulator.recordDeleteCancellation(
                                            opId, compIdx, deleteCancelCompStart, insertCancelLen);
                                    continue;
                                }

                                accumulator.recordInsertCancellation(
                                        reference.getOpId(), reference.getComponentIndex(),
                                        reference.getComponentStart(), reference.getLength());
                                accumulator.recordDeleteCancellation(
                                        opId, compIdx,
                                        deleteComponentLocalStart + (referenceStart - runStart),
                                        reference.getLength());
                            }

                            Iterator<FormatSuggestionItem> fmtIt = formatSuggestions.iterator();
                            while (fmtIt.hasNext()) {
                                FormatSuggestionItem fmt = fmtIt.next();
                                if (fmt.getReferences() == null || fmt.getReferences().isEmpty()) continue;

                                boolean touched = false;

                                for (Reference reference : fmt.getReferences()) {
                                    int referenceDocStart = reference.getReviewStart();
                                    int referenceDocEnd = referenceDocStart + reference.getLength();
                                    int overlapStart = Math.max(deleteStartPos, referenceDocStart);
                                    int overlapEnd = Math.min(deleteEndPos, referenceDocEnd);

                                    if (overlapStart >= overlapEnd) continue;

                                    touched = true;

                                    boolean formatRefBelongsToDeletedInsert = targetReferences.stream().anyMatch(insertReference ->
                                            Objects.equals(insertReference.getOpId(), reference.getOpId())
                                                    && Objects.equals(insertReference.getComponentIndex(), reference.getComponentIndex())
                                    );

                                    if (!formatRefBelongsToDeletedInsert) continue;

                                    int fmtCancelCompStart = reference.getComponentStart() + (overlapStart - referenceDocStart);
                                    int fmtCancelLen = overlapEnd - overlapStart;

                                    accumulator.recordFormatCancellation(
                                            reference.getOpId(), reference.getComponentIndex(),
                                            fmt.getAttributeKey(), fmtCancelCompStart, fmtCancelLen);
                                }

                                if (touched) {
                                    fmt.setReferences(deleteRangeFromSuggestionReferencesAndShift(
                                            fmt.getReferences(), deleteStartPos, deleteLen));
                                    if (fmt.getReferences().isEmpty()) {
                                        fmtIt.remove();
                                    }
                                }
                            }

                            remaining -= len;
                            localLogPos += len;

                        } else {
                            DeleteSuggestion.DeleteSuggestionType nextType =
                                    promotedDeleteType(currentDeleteGroup.getType(), false);

                            currentDeleteGroup.setType(nextType);
                            applyDeleteTypeToGroupRuns(runs, currentDeleteGroup.getGroupId(), nextType);

                            DeleteSuggestion runDelete = copyDeleteSuggestion(currentDeleteGroup);

                            target.setReferences(addSuggestionReference(
                                    new ArrayList<>(), target.getLogicalStart(), deleteComponentLocalStart, len, opId, compIdx));
                            target.setDeleteSuggestion(runDelete);

                            int deleteStartPos = target.getLogicalStart();
                            int deleteEndPos = deleteStartPos + len;

                            for (FormatSuggestionItem fmt : formatSuggestions) {
                                if (formatSuggestionOverlapsRange(fmt, deleteStartPos, deleteEndPos)) {
                                    String deletedGroupId = currentDeleteGroup.getGroupId();
                                    if (!fmt.getDependsOnDeleteGroupIds().contains(deletedGroupId)) {
                                        fmt.getDependsOnDeleteGroupIds().add(deletedGroupId);
                                    }
                                }
                            }

                            remaining -= len;
                            localLogPos += len;
                            cursor++;
                        }
                    }

                    if (remaining != 0) {
                        log.warn("[ATTR:DELETE:WARN] opId={} compIdx={} deleteLen={} — remaining={} after loop (expected 0)",
                                opId, compIdx, component.getDelete(), remaining);
                    }
                }
            }

            log.debug("[ATTR:OP] done opId={} localLogPos={}", opId, localLogPos);
            logRuns("AFTER_OP:" + opId, runs);
            logFormatSuggestions("AFTER_OP:" + opId, formatSuggestions);
        }

        // ── PHASE 4 ───────────────────────────────────────────────────────────
        log.info("[ATTR:PHASE] PHASE-4: removing empty format suggestions");
        int beforeSize = formatSuggestions.size();
        formatSuggestions.removeIf(fmt -> fmt.getReferences() == null || fmt.getReferences().isEmpty());
        log.info("[ATTR:PHASE4] removed {} empty formatSuggestions, {} remain", beforeSize - formatSuggestions.size(), formatSuggestions.size());

        // ── PHASE 5 ───────────────────────────────────────────────────────────
        log.info("[ATTR:PHASE] PHASE-5: building preview text for {} format suggestions", formatSuggestions.size());

        for (FormatSuggestionItem fmt : formatSuggestions) {
            fmt.setPreviewText("");
            StringBuilder texts = new StringBuilder();

            List<ReviewRange> previewRanges =
                    deriveMergedRangesFromReferences(fmt.getReferences());

            Integer prevSpanEnd = null;

            for (ReviewRange span : previewRanges) {
                int spanStart = span.getStart();
                int spanEnd = span.getStart() + span.getLength();

                if (prevSpanEnd != null && spanStart > prevSpanEnd) {
                    int finalPrevSpanEnd = prevSpanEnd;
                    boolean sawNewlineGap = runs.stream()
                            .filter(r -> r.getDeleteSuggestion() == null)
                            .anyMatch(r -> {
                                int rs = r.getLogicalStart();
                                int re = rs + r.length();
                                return re > finalPrevSpanEnd && rs < spanStart && "\n".equals(r.getText());
                            });
                    texts.append(sawNewlineGap ? " ↵ " : " ... ");
                }

                for (ReviewRun run : runs) {
                    if (run.getDeleteSuggestion() != null) continue;
                    int rs = run.getLogicalStart();
                    int re = rs + run.length();
                    if (re > spanStart && rs < spanEnd) {
                        texts.append("\n".equals(run.getText()) ? " ↵ " : run.getText());
                    }
                }

                prevSpanEnd = spanEnd;
            }

            String preview = texts.toString();
            if (preview.length() > 60) preview = preview.substring(0, 60);
            fmt.setPreviewText(preview);

            log.debug("[ATTR:PHASE5] groupId={} previewText='{}'", fmt.getGroupId(), preview);
        }

        // ── PHASE 6 ───────────────────────────────────────────────────────────
        log.info("[ATTR:PHASE] PHASE-6: flushing cancellation accumulator (empty={})", accumulator.isEmpty());

        if (!accumulator.isEmpty()) {
            NoteDto freshNote = redisService.getNote(noteId);
            NoteVersionDto noteVersion = redisService.getNoteVersion(noteId);
            boolean changed = accumulator.flushCancellationsAndReturnChanged(
                    freshNote.revisionLog()
            );
            log.info("[ATTR:PHASE6] accumulator flush changed={}", changed);

            if (changed) {
                redisService.updateNote(freshNote, noteVersion);
                notePersistenceService.saveRedisNoteToDatabase(actorEmail, noteId);
            }
        }

        // ── PHASE 7 ───────────────────────────────────────────────────────────
        log.info("[ATTR:PHASE] PHASE-7: building visual delta");
        Delta visualDelta = buildVisualDelta(runs);
        logVisualDelta("FINAL", visualDelta);

        for (TextOperation textOp : changeTextOps) {
            baseDelta = baseDelta.compose(new Delta(textOp.getDelta().ops));
        }

        log.info("[ATTR:PHASE] buildReviewProjection DONE noteId={} finalDeltaOps={} visualDeltaOps={} formatSuggestions={}",
                noteId, baseDelta.ops.size(), visualDelta.ops.size(), formatSuggestions.size());

        return new ReviewProjection(baseDelta, visualDelta, formatSuggestions);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static FormatKeyChangeType getFormatKeyChangeType(
            FormatSuggestionItem fmt,
            Object attrValue,
            Object baseValue
    ) {
        Object currentSuggestedValue = fmt.getAttributeValue();
        FormatKeyChangeType type;

        if (Objects.equals(attrValue, currentSuggestedValue)) {
            type = FormatKeyChangeType.NO_OP;
        } else if (Objects.equals(attrValue, baseValue)) {
            type = FormatKeyChangeType.CANCEL;
        } else {
            type = FormatKeyChangeType.REPLACE;
        }

        return type;
    }

    private Delta buildVisualDelta(List<ReviewRun> runs) {
        log.debug("[ATTR:VDELTA] collapsing {} runs", runs.size());

        List<ReviewRun> collapsed = new ArrayList<>();

        for (ReviewRun run : runs) {
            if (run == null || run.length() <= 0) {
                continue;
            }

            ReviewRun last = collapsed.isEmpty() ? null : collapsed.get(collapsed.size() - 1);

            Map<String, Object> lastEffective =
                    last != null ? getEffectiveAttrs(last) : Collections.emptyMap();

            Map<String, Object> runEffective = getEffectiveAttrs(run);

            boolean canMerge =
                    last != null
                            && last.isText()
                            && run.isText()
                            && !"\n".equals(run.getText())
                            && !"\n".equals(last.getText())
                            && attrsEq(lastEffective, runEffective)
                            && Objects.equals(
                            last.getInsertSuggestion() != null
                                    ? last.getInsertSuggestion().getGroupId()
                                    : null,
                            run.getInsertSuggestion() != null
                                    ? run.getInsertSuggestion().getGroupId()
                                    : null
                    )
                            && Objects.equals(
                            last.getDeleteSuggestion() != null
                                    ? last.getDeleteSuggestion().getGroupId()
                                    : null,
                            run.getDeleteSuggestion() != null
                                    ? run.getDeleteSuggestion().getGroupId()
                                    : null
                    )
                            && Objects.equals(
                            last.getDeleteSuggestion() != null
                                    ? last.getDeleteSuggestion().getType()
                                    : null,
                            run.getDeleteSuggestion() != null
                                    ? run.getDeleteSuggestion().getType()
                                    : null
                    )
                            && (last.getInsertSuggestion() == null) == (run.getInsertSuggestion() == null)
                            && (last.getDeleteSuggestion() == null) == (run.getDeleteSuggestion() == null)
                            && attrsEq(
                            last.getBaseAttributes() != null
                                    ? last.getBaseAttributes()
                                    : Collections.emptyMap(),
                            run.getBaseAttributes() != null
                                    ? run.getBaseAttributes()
                                    : Collections.emptyMap()
                    );

            if (canMerge) {
                log.debug(
                        "[ATTR:VDELTA] merging run text='{}' into last text='{}'",
                        runTextForLog(run),
                        runTextForLog(last)
                );

                last.setText(last.getText() + run.getText());
                last.setReferences(
                        appendSuggestionReferences(
                                last.getReferences(),
                                run.getReferences()
                        )
                );

                continue;
            }

            ReviewRun.ReviewRunBuilder builder = ReviewRun.builder()
                    .baseAttributes(new LinkedHashMap<>(
                            run.getBaseAttributes() != null
                                    ? run.getBaseAttributes()
                                    : Collections.emptyMap()
                    ))
                    .suggestionAttributes(new LinkedHashMap<>(
                            run.getSuggestionAttributes() != null
                                    ? run.getSuggestionAttributes()
                                    : Collections.emptyMap()
                    ))
                    .references(cloneSuggestionReferences(run.getReferences()))
                    .logicalStart(run.getLogicalStart())
                    .insertSuggestion(
                            run.getInsertSuggestion() != null
                                    ? copyInsertSuggestion(run.getInsertSuggestion())
                                    : null
                    )
                    .deleteSuggestion(
                            run.getDeleteSuggestion() != null
                                    ? copyDeleteSuggestion(run.getDeleteSuggestion())
                                    : null
                    );

            if (run.isEmbed()) {
                builder.embed(cloneEmbed(run.getEmbed()));
            } else {
                builder.text(run.getText());
            }

            collapsed.add(builder.build());
        }

        log.debug(
                "[ATTR:VDELTA] after collapse: {} runs -> {} collapsed",
                runs.size(),
                collapsed.size()
        );

        Delta delta = new Delta();

        for (ReviewRun run : collapsed) {
            if (run == null || run.length() <= 0) {
                continue;
            }

            Map<String, Object> baseAttrs =
                    run.getBaseAttributes() != null
                            ? run.getBaseAttributes()
                            : Collections.emptyMap();

            Map<String, Object> suggestionAttrs =
                    run.getSuggestionAttributes() != null
                            ? run.getSuggestionAttributes()
                            : Collections.emptyMap();

            boolean isInsertedPending = run.getInsertSuggestion() != null;
            boolean isDeletedPending = run.getDeleteSuggestion() != null && !isInsertedPending;
            boolean isDeletedNewline =
                    run.isText()
                            && "\n".equals(run.getText())
                            && run.getDeleteSuggestion() != null;

            Map<String, Object> attrs = new LinkedHashMap<>();

            if (!baseAttrs.isEmpty()) {
                attrs.putAll(baseAttrs);
            }

            if (!suggestionAttrs.isEmpty()) {
                attrs.putAll(suggestionAttrs);
            }

            if (run.getInsertSuggestion() != null) {
                Map<String, Object> insertPayload = new LinkedHashMap<>();
                insertPayload.put("groupId", run.getInsertSuggestion().getGroupId());
                insertPayload.put("actorEmail", run.getInsertSuggestion().getActorEmail());
                insertPayload.put("createdAt", run.getInsertSuggestion().getCreatedAt());
                insertPayload.put("references", run.getReferences());
                insertPayload.put("baseAttributes", !baseAttrs.isEmpty() ? baseAttrs : null);
                insertPayload.put(
                        "suggestionAttributes",
                        !suggestionAttrs.isEmpty() ? suggestionAttrs : null
                );

                attrs.put("suggestion-insert", insertPayload);

                log.debug(
                        "[ATTR:VDELTA] RETAIN(insert) text='{}' groupId={} logicalStart={}",
                        runTextForLog(run),
                        run.getInsertSuggestion().getGroupId(),
                        run.getLogicalStart()
                );
            }

            if (run.getDeleteSuggestion() != null) {
                Map<String, Object> deletePayload = new LinkedHashMap<>();
                deletePayload.put("groupId", run.getDeleteSuggestion().getGroupId());
                deletePayload.put("actorEmail", run.getDeleteSuggestion().getActorEmail());
                deletePayload.put("createdAt", run.getDeleteSuggestion().getCreatedAt());
                deletePayload.put("references", run.getReferences());
                deletePayload.put("baseAttributes", !baseAttrs.isEmpty() ? baseAttrs : null);
                deletePayload.put(
                        "suggestionAttributes",
                        !suggestionAttrs.isEmpty() ? suggestionAttrs : null
                );
                deletePayload.put("type", run.getDeleteSuggestion().getType());

                DeleteSuggestion.DeleteSuggestionType type =
                        run.getDeleteSuggestion().getType() != null
                                ? run.getDeleteSuggestion().getType()
                                : DeleteSuggestion.DeleteSuggestionType.TEXT;

                if (type == DeleteSuggestion.DeleteSuggestionType.SINGLE_LINE) {
                    attrs.put("suggestion-delete-singleline", deletePayload);
                } else if (type == DeleteSuggestion.DeleteSuggestionType.MULTI_LINE) {
                    attrs.put("suggestion-delete-multiline", deletePayload);
                } else {
                    attrs.put("suggestion-delete", deletePayload);
                }

                log.debug(
                        "[ATTR:VDELTA] {} text='{}' groupId={} logicalStart={}",
                        isDeletedNewline ? "INSERT(del-newline)" : "INSERT(delete)",
                        runTextForLog(run),
                        run.getDeleteSuggestion().getGroupId(),
                        run.getLogicalStart()
                );
            }

            if (!isInsertedPending && !isDeletedPending && !isDeletedNewline) {
                log.debug(
                        "[ATTR:VDELTA] RETAIN(base) len={} logicalStart={}",
                        run.length(),
                        run.getLogicalStart()
                );
            }

            if (isDeletedPending) {
                Object insertValue;

                if (run.isEmbed()) {
                    insertValue = cloneEmbed(run.getEmbed());
                } else if (isDeletedNewline) {
                    DeleteSuggestion.DeleteSuggestionType type =
                            run.getDeleteSuggestion().getType() != null
                                    ? run.getDeleteSuggestion().getType()
                                    : DeleteSuggestion.DeleteSuggestionType.TEXT;

                    insertValue = type == DeleteSuggestion.DeleteSuggestionType.SINGLE_LINE
                            ? " ↵ "
                            : run.getText();
                } else {
                    insertValue = run.getText();
                }

                delta.insert(insertValue, attrs.isEmpty() ? null : attrs);
            } else {
                delta.retain(run.length(), attrs.isEmpty() ? null : attrs);
            }
        }

        log.debug("[ATTR:VDELTA] built delta with {} ops", delta.ops.size());
        return delta;
    }

    private enum InsertContentKind {
        TEXT,
        EMBED
    }

    private static InsertContentKind kindOfRun(ReviewRun run) {
        if (run == null) return null;
        return run.isEmbed() ? InsertContentKind.EMBED : InsertContentKind.TEXT;
    }

    private static InsertContentKind kindOfFragment(InsertFragment fragment) {
        if (fragment == null) return null;
        return fragment.isEmbed() ? InsertContentKind.EMBED : InsertContentKind.TEXT;
    }

    private static boolean sameInsertContentKind(
            ReviewRun run,
            InsertContentKind kind
    ) {
        if (run == null || kind == null) return false;
        return kindOfRun(run) == kind;
    }

    private static InsertSuggestion compatibleAdjacentInsertSuggestion(
            ReviewRun run,
            String authorEmail,
            InsertContentKind insertKind
    ) {
        if (run == null) return null;
        if (run.getInsertSuggestion() == null) return null;
        if (!authorEmail.equals(run.getInsertSuggestion().getActorEmail())) return null;
        if (!sameInsertContentKind(run, insertKind)) return null;

        return run.getInsertSuggestion();
    }
}