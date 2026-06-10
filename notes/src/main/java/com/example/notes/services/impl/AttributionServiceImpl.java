package com.example.notes.services.impl;

import com.example.notes.dto.attribution.*;
import com.example.notes.dto.ot.Delta;
import com.example.notes.dto.ot.Op;
import com.example.notes.dto.ot.TextOperation;
import com.example.notes.services.AttributionService;
import com.example.notes.utils.QuillDeltaUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

import static com.example.notes.utils.AttributionHelpers.*;

@Slf4j
@Service
public class AttributionServiceImpl implements AttributionService {

    public AttributionServiceImpl() {}

    // ─────────────────────────────────────────────────────────────────────────
    // Main method
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public AttributionBuildResult buildReviewProjection(
            String actorEmail,
            UUID noteId,
            List<TextOperation> baseTextOps,
            List<TextOperation> changeTextOps,
            List<TextOperation> mutableRevisionLog,
            AttributionViewMode mode
    ) {

        resetGroupCounter();

        // ── PHASE 1 ───────────────────────────────────────────────────────────
        Delta baseDelta = QuillDeltaUtils.emptyDocument();

        for (TextOperation textOp : baseTextOps) {
            baseDelta = baseDelta.compose(new Delta(textOp.getDelta().ops));
        }

        // ── PHASE 2 ───────────────────────────────────────────────────────────
        List<ReviewRun> runs = new ArrayList<>();
        int seedPos = 0;

        for (Op op : baseDelta.ops) {
            Map<String, Object> opAttrs = op.getAttributes() != null
                    ? new LinkedHashMap<>(op.getAttributes())
                    : new LinkedHashMap<>();

            if (op.getInsert() instanceof String insertStr) {
                Map<String, Object> inlineAttrs = onlyInlineAttrs(opAttrs);
                Map<String, Object> blockAttrs = onlyBlockAttrs(opAttrs);

                String[] parts = insertStr.split("\n", -1);

                for (int i = 0; i < parts.length; i++) {
                    if (!parts[i].isEmpty()) {
                        runs.add(
                                ReviewRun.builder()
                                        .id(reviewRunIdForBase(seedPos))
                                        .text(parts[i])
                                        .baseAttributes(new LinkedHashMap<>(inlineAttrs))
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
                                        .id(reviewRunIdForBase(seedPos))
                                        .text("\n")
                                        .baseAttributes(new LinkedHashMap<>(blockAttrs))
                                        .suggestionAttributes(new LinkedHashMap<>())
                                        .references(new ArrayList<>())
                                        .logicalStart(seedPos)
                                        .build()
                        );
                        seedPos += 1;
                    }
                }
            }  else if (op.getInsert() instanceof Map<?, ?> embed) {
                runs.add(
                        ReviewRun.builder()
                                .id(reviewRunIdForBase(seedPos))
                                .embed(cloneEmbed(embed))
                                .baseAttributes(new LinkedHashMap<>(onlyInlineAttrs(opAttrs)))
                                .suggestionAttributes(new LinkedHashMap<>())
                                .references(new ArrayList<>())
                                .logicalStart(seedPos)
                                .build()
                );

                seedPos += 1;
            }
        }

        // ── PHASE 3 ───────────────────────────────────────────────────────────

        List<FormatSuggestionItem> formatSuggestions = new ArrayList<>();
        List<BlockFormatSuggestionItem> blockFormatSuggestions = new ArrayList<>();
        AttributionCancellationAccumulator accumulator = new AttributionCancellationAccumulator();

        for (TextOperation textOp : changeTextOps) {
            String opId = textOp.getOpId();
            String authorEmail = textOp.getActorEmail();
            String createdAt = textOp.getCreatedAt().toString();

            int localLogPos = 0;
            InsertSuggestion currentInsertGroup = null;
            DeleteSuggestion currentDeleteGroup = null;
            FormatSuggestionItem currentFormatGroup = null;
            Map<BlockGroupKey, BlockFormatSuggestionItem> currentBlockGroups = new LinkedHashMap<>();

            List<Op> components = textOp.getDelta().ops;

            for (int compIdx = 0; compIdx < components.size(); compIdx++) {
                Op component = components.get(compIdx);

                // ── CASE A: plain retain ──────────────────────────────────────
                if (component.isRetain() && component.getAttributes() == null) {
                    boolean isLastOp = compIdx == components.size() - 1;

                    if (isLastOp) break;

                    currentInsertGroup = null;
                    currentDeleteGroup = null;

                    int retainLen = (int) component.getRetain();
                    boolean newlineOnly = isOnlyNewlineRetain(runs, localLogPos, retainLen);

                    if (!newlineOnly) {
                        currentFormatGroup = null;
                    }

                    localLogPos += retainLen;
                }

                // ── CASE B: retain with attributes (format suggestion) ────────
                else if (component.isRetain() && component.getAttributes() != null) {
                    currentInsertGroup = null;
                    currentDeleteGroup = null;

                    int retainLen = (int) component.getRetain();

                    Map<String, Object> rawIncomingAttrs =
                            new LinkedHashMap<>(component.getAttributes());

                    Map<String, Object> inlineIncomingAttrs = onlyInlineAttrs(rawIncomingAttrs);
                    Map<String, Object> blockIncomingAttrs = onlyBlockAttrs(rawIncomingAttrs);

                    if (!blockIncomingAttrs.isEmpty()) {
                        RunPosition blockStartPos = findRunPos(runs, localLogPos);
                        int blockRunIdx = blockStartPos.idx();
                        int blockOffset = blockStartPos.offset();

                        if (blockOffset > 0 && blockRunIdx < runs.size()) {
                            blockRunIdx = splitAt(runs, blockRunIdx, blockOffset);
                        }

                        int remaining = retainLen;
                        int cursor = blockRunIdx;

                        while (remaining > 0 && cursor < runs.size()) {
                            ReviewRun run = runs.get(cursor);

                            if (run.getDeleteSuggestion() != null) {
                                cursor++;
                                continue;
                            }

                            if (run.isText() && run.length() > remaining) {
                                splitAt(runs, cursor, remaining);
                            }

                            ReviewRun target = runs.get(cursor);
                            int spanLen = target.length();
                            int componentStart = retainLen - remaining;

                            if (isBlockTargetRun(target)) {
                                for (Map.Entry<String, Object> entry : blockIncomingAttrs.entrySet()) {
                                    applyBlockAttributeToNewlineRun(
                                            runs,
                                            blockFormatSuggestions,
                                            accumulator,
                                            target,
                                            entry.getKey(),
                                            entry.getValue(),
                                            authorEmail,
                                            createdAt,
                                            opId,
                                            compIdx,
                                            componentStart,
                                            currentBlockGroups
                                    );
                                }
                            }

                            remaining -= spanLen;
                            cursor++;
                        }
                    }

                    else if (!inlineIncomingAttrs.isEmpty()) {
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

                            if (run.getDeleteSuggestion() != null || isBlockTargetRun(run)) {
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

                            Map<String, Object> baseAttrs = target.getBaseAttributes() != null
                                    ? target.getBaseAttributes()
                                    : Collections.emptyMap();

                            for (Map.Entry<String, Object> entry : inlineIncomingAttrs.entrySet()) {
                                String attrKey = entry.getKey();
                                Object attrValue = entry.getValue();
                                Object baseValue = baseAttrs.get(attrKey);

                                List<FormatSuggestionItem> coveringFormats = formatSuggestions.stream()
                                        .filter(f -> attrKey.equals(f.getAttributeKey()))
                                        .filter(f -> formatSuggestionCoversRange(f, spanStart, spanLen))
                                        .toList();

                                for (FormatSuggestionItem fmt : new ArrayList<>(coveringFormats)) {
                                    FormatKeyChangeType type = getFormatKeyChangeType(fmt, attrValue, baseValue);

                                    if (type == FormatKeyChangeType.CANCEL || type == FormatKeyChangeType.REPLACE) {
                                        for (Reference reference : fmt.getReferences()) {
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
                                                    attrKey
                                            );
                                        }

                                        fmt.setReferences(
                                                removeRangeFromSuggestionReferencesWithoutShift(
                                                        fmt.getReferences(),
                                                        spanStart,
                                                        spanLen
                                                )
                                        );

                                        if (fmt.getReferences().isEmpty()) {
                                            formatSuggestions.remove(fmt);
                                        }

                                        if (target.getSuggestionAttributes() != null) {
                                            target.getSuggestionAttributes().remove(attrKey);
                                        }
                                    }
                                }

                                if (Objects.equals(baseValue, attrValue)) {
                                    continue;
                                }

                                Map<String, Object> attrsToOverlay = new HashMap<>();
                                attrsToOverlay.put(attrKey, attrValue);

                                target.setSuggestionAttributes(
                                        overlayAttrsPreserveNull(
                                                target.getSuggestionAttributes(),
                                                attrsToOverlay
                                        )
                                );

                                if (currentFormatGroup == null
                                        || !attrKey.equals(currentFormatGroup.getAttributeKey())
                                        || !Objects.equals(attrValue, currentFormatGroup.getAttributeValue())) {

                                    currentFormatGroup = findOrCreateCompatibleFormatSuggestion(
                                            formatSuggestions,
                                            authorEmail,
                                            createdAt,
                                            attrKey,
                                            attrValue,
                                            spanStart,
                                            spanEnd
                                    );
                                }

                                if (target.getInsertSuggestion() != null) {
                                    addInsertDependency(
                                            currentFormatGroup,
                                            target.getInsertSuggestion().getGroupId()
                                    );
                                }

                                int componentStart = retainLen - remaining;

                                currentFormatGroup.setReferences(
                                        addSuggestionReference(
                                                currentFormatGroup.getReferences(),
                                                spanStart,
                                                componentStart,
                                                spanLen,
                                                opId,
                                                compIdx
                                        )
                                );
                            }

                            remaining -= spanLen;
                            cursor++;
                        }
                    }

                    localLogPos += retainLen;
                }

                // ── CASE C: insert ────────────────────────────────────────────
                else if (component.isInsert()) {
                    currentDeleteGroup = null;
                    currentFormatGroup = null;

                    List<InsertFragment> fragments = buildInsertFragments(component.getInsert());

                    if (fragments.isEmpty()) {
                        continue;
                    }

                    InsertContentKind insertKind = kindOfFragment(fragments.get(0));

                    int shiftLen = fragments.stream()
                            .mapToInt(InsertFragment::length)
                            .sum();

                    RunPosition insertPos = findRunPos(runs, localLogPos);
                    int runIndex = insertPos.idx();
                    int insertAbsPos = insertPos.absPos();

                    int insertAtIdx = runIndex;
                    int insertOffset = insertPos.offset();

                    if (insertOffset > 0 && runIndex < runs.size()) {
                        insertAtIdx = splitAt(runs, runIndex, insertOffset);
                    }

                    ReviewRun prevGroupingRun =
                            effectivePreviousRunForInsertGrouping(runs, insertAtIdx);

                    ReviewRun nextGroupingRun =
                            effectiveNextRunForInsertGrouping(runs, insertAtIdx);

                    if (currentInsertGroup == null) {
                        InsertSuggestion prevAdj =
                                compatibleAdjacentInsertSuggestion(
                                        prevGroupingRun,
                                        authorEmail,
                                        insertKind
                                );

                        InsertSuggestion nextAdj =
                                compatibleAdjacentInsertSuggestion(
                                        nextGroupingRun,
                                        authorEmail,
                                        insertKind
                                );

                        InsertSuggestion adj = prevAdj != null ? prevAdj : nextAdj;

                        currentInsertGroup = adj != null
                                ? copyInsertSuggestion(adj)
                                : InsertSuggestion.builder()
                                .groupId(nextId())
                                .actorEmail(authorEmail)
                                .createdAt(createdAt)
                                .build();

                    } else {
                        boolean currentGroupStillCompatible =
                                (prevGroupingRun != null
                                        && prevGroupingRun.getInsertSuggestion() != null
                                        && currentInsertGroup.getGroupId().equals(
                                        prevGroupingRun.getInsertSuggestion().getGroupId()
                                )
                                        && sameInsertContentKind(prevGroupingRun, insertKind))
                                        ||
                                        (nextGroupingRun != null
                                                && nextGroupingRun.getInsertSuggestion() != null
                                                && currentInsertGroup.getGroupId().equals(
                                                nextGroupingRun.getInsertSuggestion().getGroupId()
                                        )
                                                && sameInsertContentKind(nextGroupingRun, insertKind));

                        if (!currentGroupStillCompatible) {
                            currentInsertGroup = InsertSuggestion.builder()
                                    .groupId(nextId())
                                    .actorEmail(authorEmail)
                                    .createdAt(createdAt)
                                    .build();

                        } else if (createdAt.compareTo(currentInsertGroup.getCreatedAt()) > 0) {
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

                    Map<String, Object> prevEffectiveAttrs = getEffectiveAttrs(prevGroupingRun);
                    Map<String, Object> nextEffectiveAttrs = getEffectiveAttrs(nextGroupingRun);

                    if (!ownAttrs.isEmpty()
                            && prevGroupingRun != null
                            && prevGroupingRun.getInsertSuggestion() != null
                            && !authorEmail.equals(prevGroupingRun.getInsertSuggestion().getActorEmail())
                            && !prevEffectiveAttrs.isEmpty()) {

                        Map<String, Object> inherited = intersectAttrs(ownAttrs, prevEffectiveAttrs);

                        if (!inherited.isEmpty()) {
                            InsertGroupCollection prevGroup = collectInsertGroupRunsWithAttrs(
                                    runs,
                                    prevGroupingRun.getInsertSuggestion().getGroupId(),
                                    inherited
                            );

                            if (prevGroup != null) {
                                for (Map.Entry<String, Object> inheritedEntry : inherited.entrySet()) {
                                    String inheritedKey = inheritedEntry.getKey();
                                    Object inheritedValue = inheritedEntry.getValue();

                                    Map<String, Object> singleInherited =
                                            new LinkedHashMap<>(Map.of(inheritedKey, inheritedValue));

                                    FormatSuggestionItem g = findOrCreateFormatSuggestionByIdentity(
                                            formatSuggestions,
                                            prevGroupingRun.getInsertSuggestion().getActorEmail(),
                                            prevGroupingRun.getInsertSuggestion().getCreatedAt(),
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

                                    addInsertDependency(g, prevGroupingRun.getInsertSuggestion().getGroupId());
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
                            }
                        }
                    }

                    if (!ownAttrs.isEmpty()
                            && nextGroupingRun != null
                            && nextGroupingRun.getInsertSuggestion() != null
                            && !authorEmail.equals(nextGroupingRun.getInsertSuggestion().getActorEmail())
                            && !nextEffectiveAttrs.isEmpty()) {

                        Map<String, Object> inherited = intersectAttrs(ownAttrs, nextEffectiveAttrs);

                        if (!inherited.isEmpty()) {
                            InsertGroupCollection nextGroup = collectInsertGroupRunsWithAttrs(
                                    runs,
                                    nextGroupingRun.getInsertSuggestion().getGroupId(),
                                    inherited
                            );

                            if (nextGroup != null) {
                                for (Map.Entry<String, Object> inheritedEntry : inherited.entrySet()) {
                                    String inheritedKey = inheritedEntry.getKey();
                                    Object inheritedValue = inheritedEntry.getValue();

                                    Map<String, Object> singleInherited =
                                            new LinkedHashMap<>(Map.of(inheritedKey, inheritedValue));

                                    FormatSuggestionItem g = findOrCreateFormatSuggestionByIdentity(
                                            formatSuggestions,
                                            nextGroupingRun.getInsertSuggestion().getActorEmail(),
                                            nextGroupingRun.getInsertSuggestion().getCreatedAt(),
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

                                    addInsertDependency(g, nextGroupingRun.getInsertSuggestion().getGroupId());
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
                            }
                        }
                    }

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

                    shiftBlockFormatSuggestionReferences(
                            blockFormatSuggestions,
                            insertAbsPos,
                            shiftLen,
                            Collections.emptySet()
                    );

                    int spliceAt = insertAtIdx;
                    int runPos = insertAbsPos;

                    for (InsertFragment fragment : fragments) {
                        List<Reference> runRefs = addSuggestionReference(
                                new ArrayList<>(),
                                runPos,
                                fragment.componentStart(),
                                fragment.length(),
                                opId,
                                compIdx
                        );

                        ReviewRun.ReviewRunBuilder builder = ReviewRun.builder()
                                .id(reviewRunIdForReference(opId, compIdx, fragment.componentStart(), runPos))
                                .baseAttributes(fragment.newline()
                                        ? new LinkedHashMap<>()
                                        : new LinkedHashMap<>(onlyInlineAttrs(ownAttrs)))
                                .suggestionAttributes(fragment.newline()
                                        ? new LinkedHashMap<>(onlyBlockAttrs(component.getAttributes()))
                                        : new LinkedHashMap<>(inheritedSuggestionAttrs))
                                .references(runRefs)
                                .logicalStart(runPos);

                        if (fragment.newline()) {
                            NewlineSuggestion newlineSuggestion =
                                    createNewlineSuggestionForInsertedNewline(
                                            authorEmail,
                                            createdAt,
                                            runRefs
                                    );

                            builder.newlineSuggestion(newlineSuggestion);
                            builder.text("\n");
                        } else {
                            builder.insertSuggestion(copyInsertSuggestion(currentInsertGroup));

                            if (fragment.isEmbed()) {
                                builder.embed(fragment.embed());
                            } else {
                                builder.text(fragment.text());
                            }
                        }

                        ReviewRun newRun = builder.build();

                        runs.add(spliceAt++, newRun);

                        if (fragment.newline()) {
                            Map<String, Object> insertedBlockAttrs = onlyBlockAttrs(component.getAttributes());

                            for (Map.Entry<String, Object> entry : insertedBlockAttrs.entrySet()) {
                                applyBlockAttributeToNewlineRun(
                                        runs,
                                        blockFormatSuggestions,
                                        accumulator,
                                        newRun,
                                        entry.getKey(),
                                        entry.getValue(),
                                        authorEmail,
                                        createdAt,
                                        opId,
                                        compIdx,
                                        fragment.componentStart(),
                                        currentBlockGroups
                                );
                            }
                        }

                        runPos += fragment.length();
                    }

                    for (int i = spliceAt; i < runs.size(); i++) {
                        runs.get(i).setLogicalStart(runs.get(i).getLogicalStart() + shiftLen);
                    }

                    localLogPos += shiftLen;
                }

                // ── CASE D: delete ────────────────────────────────────────────
                else if (component.isDelete()) {
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
                        ReviewRun prevDeleteGroupingRun =
                                effectivePreviousRunForDeleteGrouping(runs, cursor);

                        ReviewRun nextDeleteGroupingRun =
                                effectiveNextRunForDeleteGrouping(runs, cursor);

                        DeleteSuggestion prevAdj =
                                prevDeleteGroupingRun != null
                                        && prevDeleteGroupingRun.getDeleteSuggestion() != null
                                        && authorEmail.equals(prevDeleteGroupingRun.getDeleteSuggestion().getActorEmail())
                                        ? prevDeleteGroupingRun.getDeleteSuggestion()
                                        : null;

                        DeleteSuggestion nextAdj =
                                nextDeleteGroupingRun != null
                                        && nextDeleteGroupingRun.getDeleteSuggestion() != null
                                        && authorEmail.equals(nextDeleteGroupingRun.getDeleteSuggestion().getActorEmail())
                                        ? nextDeleteGroupingRun.getDeleteSuggestion()
                                        : null;

                        if (prevAdj != null) {
                            currentDeleteGroup = copyDeleteSuggestion(prevAdj);

                            if (nextAdj != null && !nextAdj.getGroupId().equals(prevAdj.getGroupId())) {
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
                                    .type(DeleteSuggestion.DeleteSuggestionType.TEXT)
                                    .build();
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
                            cursor++;
                            continue;
                        }

                        if (run.isText() && remaining < run.length()) {
                            splitAt(runs, cursor, remaining);
                        }

                        ReviewRun target = runs.get(cursor);
                        int len = target.length();

                        if (target.getInsertSuggestion() != null || target.getNewlineSuggestion() != null) {
                            int runStart = target.getLogicalStart();
                            int runEnd = runStart + target.length();

                            List<Reference> targetReferences = target.getReferences();

                            int deleteStartPos = target.getLogicalStart();
                            int deleteLen = target.length();
                            int deleteEndPos = deleteStartPos + deleteLen;

                            if (isBlockTargetRun(target)) {
                                cancelBlockSuggestionsForDeletedNewline(
                                        blockFormatSuggestions,
                                        accumulator,
                                        target
                                );
                            }

                            runs.remove(cursor);

                            for (int i = cursor; i < runs.size(); i++) {
                                runs.get(i).setLogicalStart(runs.get(i).getLogicalStart() - deleteLen);
                            }

                            deleteRangeFromRunReferencesAndShift(runs, deleteStartPos, deleteLen);
                            deleteRangeFromFormatSuggestionReferencesAndShift(formatSuggestions, deleteStartPos, deleteLen);

                            deleteRangeFromBlockFormatSuggestionReferencesAndShift(
                                    blockFormatSuggestions,
                                    deleteStartPos,
                                    deleteLen
                            );

                            formatSuggestions.removeIf(fmt ->
                                    fmt.getReferences() == null || fmt.getReferences().isEmpty()
                            );

                            for (Reference reference : targetReferences) {
                                int referenceStart = reference.getReviewStart();
                                int referenceEnd = referenceStart + reference.getLength();

                                int overlapStart = Math.max(runStart, referenceStart);
                                int overlapEnd = Math.min(runEnd, referenceEnd);

                                if (overlapStart >= overlapEnd) continue;

                                int insertCancelCompStart =
                                        reference.getComponentStart() + (overlapStart - referenceStart);

                                int insertCancelLen = overlapEnd - overlapStart;

                                int deleteCancelCompStart =
                                        deleteComponentLocalStart + (overlapStart - runStart);

                                accumulator.recordInsertCancellation(
                                        reference.getOpId(),
                                        reference.getComponentIndex(),
                                        insertCancelCompStart,
                                        insertCancelLen
                                );

                                accumulator.recordDeleteCancellation(
                                        opId,
                                        compIdx,
                                        deleteCancelCompStart,
                                        insertCancelLen
                                );
                            }

                            Iterator<FormatSuggestionItem> fmtIt = formatSuggestions.iterator();

                            while (fmtIt.hasNext()) {
                                FormatSuggestionItem fmt = fmtIt.next();

                                if (fmt.getReferences() == null || fmt.getReferences().isEmpty()) {
                                    continue;
                                }

                                boolean touched = false;

                                for (Reference reference : fmt.getReferences()) {
                                    int referenceDocStart = reference.getReviewStart();
                                    int referenceDocEnd = referenceDocStart + reference.getLength();

                                    int overlapStart = Math.max(deleteStartPos, referenceDocStart);
                                    int overlapEnd = Math.min(deleteEndPos, referenceDocEnd);

                                    if (overlapStart >= overlapEnd) continue;

                                    touched = true;

                                    boolean formatRefBelongsToDeletedInsert =
                                            targetReferences.stream().anyMatch(insertReference ->
                                                    Objects.equals(insertReference.getOpId(), reference.getOpId())
                                                            && Objects.equals(
                                                            insertReference.getComponentIndex(),
                                                            reference.getComponentIndex()
                                                    )
                                            );

                                    if (!formatRefBelongsToDeletedInsert) continue;

                                    int fmtCancelCompStart =
                                            reference.getComponentStart() + (overlapStart - referenceDocStart);

                                    int fmtCancelLen = overlapEnd - overlapStart;

                                    accumulator.recordFormatCancellation(
                                            reference.getOpId(),
                                            reference.getComponentIndex(),
                                            fmtCancelCompStart,
                                            fmtCancelLen,
                                            fmt.getAttributeKey()
                                    );
                                }

                                if (touched) {
                                    fmt.setReferences(
                                            deleteRangeFromSuggestionReferencesAndShift(
                                                    fmt.getReferences(),
                                                    deleteStartPos,
                                                    deleteLen
                                            )
                                    );

                                    if (fmt.getReferences().isEmpty()) {
                                        fmtIt.remove();
                                    }
                                }
                            }

                            remaining -= len;
                        } else {
                            boolean deletingBaseNewline = isBlockTargetRun(target);

                            if (deletingBaseNewline) {
                                cancelBlockSuggestionsForDeletedNewline(
                                        blockFormatSuggestions,
                                        accumulator,
                                        target
                                );
                            }

                            DeleteSuggestion.DeleteSuggestionType nextType =
                                    promotedDeleteType(
                                            currentDeleteGroup.getType(),
                                            deletingBaseNewline
                                    );

                            currentDeleteGroup.setType(nextType);
                            applyDeleteTypeToGroupRuns(
                                    runs,
                                    currentDeleteGroup.getGroupId(),
                                    nextType
                            );

                            DeleteSuggestion runDelete = copyDeleteSuggestion(currentDeleteGroup);

                            target.setReferences(
                                    addSuggestionReference(
                                            new ArrayList<>(),
                                            target.getLogicalStart(),
                                            deleteComponentLocalStart,
                                            len,
                                            opId,
                                            compIdx
                                    )
                            );

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
                }
            }
        }

        // ── PHASE 4 ───────────────────────────────────────────────────────────
        formatSuggestions.removeIf(fmt -> fmt.getReferences() == null || fmt.getReferences().isEmpty());

        blockFormatSuggestions.removeIf(fmt ->
                fmt.getReferences() == null || fmt.getReferences().isEmpty()
        );

        // ── PHASE 5 ───────────────────────────────────────────────────────────

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
        }

        for (BlockFormatSuggestionItem fmt : blockFormatSuggestions) {
            fmt.setPreviewText("");

            StringBuilder texts = new StringBuilder();
            List<ReviewRange> previewRanges = deriveMergedRangesFromReferences(fmt.getReferences());

            Integer prevSpanEnd = null;

            for (ReviewRange span : previewRanges) {
                int newlinePos = span.getStart();

                if (prevSpanEnd != null && newlinePos > prevSpanEnd) {
                    texts.append(" ... ");
                }

                texts.append(getLinePreviewForNewline(runs, newlinePos));

                prevSpanEnd = span.getStart() + span.getLength();
            }

            String preview = texts.toString();
            if (preview.length() > 60) preview = preview.substring(0, 60);

            fmt.setPreviewText(preview);
        }

        // ── PHASE 6 ───────────────────────────────────────────────────────────
        boolean revisionLogChanged = false;

        if (mode == AttributionViewMode.REVIEW && !accumulator.isEmpty()) {
            if (mutableRevisionLog == null) {
                throw new IllegalStateException(
                        "mutableRevisionLog is required when REVIEW mode flushes attribution cancellations."
                );
            }

            revisionLogChanged =
                    accumulator.flushCancellationsAndReturnChanged(mutableRevisionLog);
        }

        // ── PHASE 7 ───────────────────────────────────────────────────────────
        classifyNewlineSuggestions(runs);

        attachStandaloneNewlineDependenciesToBlockSuggestions(
                runs,
                blockFormatSuggestions
        );

        normalizeContinuingBlockFormatGroups(
                runs,
                blockFormatSuggestions
        );

        syncBlockFormatDependenciesFromTargetNewlines(
                runs,
                blockFormatSuggestions
        );

        Delta visualDelta = buildVisualDelta(runs, mode);

        for (TextOperation textOp : changeTextOps) {
            baseDelta = baseDelta.compose(new Delta(textOp.getDelta().ops));
        }
        baseDelta = QuillDeltaUtils.ensureTerminalNewline(baseDelta);

        ReviewProjection projection = new ReviewProjection(
                baseDelta,
                visualDelta,
                formatSuggestions,
                blockFormatSuggestions
        );

        return new AttributionBuildResult(projection, revisionLogChanged);
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

    private Delta buildVisualDelta(List<ReviewRun> runs, AttributionViewMode mode) {
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
                    .newlineSuggestion(
                            run.getNewlineSuggestion() != null
                                    ? copyNewlineSuggestion(run.getNewlineSuggestion())
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

            boolean isInsertedPending =
                    run.getInsertSuggestion() != null || run.getNewlineSuggestion() != null;

            boolean isDeletedPending =
                    run.getDeleteSuggestion() != null && !isInsertedPending;

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
            }

            if (run.getNewlineSuggestion() != null) {
                attrs.put(
                        "suggestion-newline",
                        buildNewlineSuggestionPayload(run, false)
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
            }

            boolean isPlainBaseReviewRun =
                    run.getInsertSuggestion() == null
                            && run.getNewlineSuggestion() == null
                            && run.getDeleteSuggestion() == null;

            if (isPlainBaseReviewRun) {
                Map<String, Object> basePayload = new LinkedHashMap<>();

                basePayload.put(
                        "baseAttributes",
                        !baseAttrs.isEmpty() ? new LinkedHashMap<>(baseAttrs) : null
                );

                basePayload.put(
                        "suggestionAttributes",
                        !suggestionAttrs.isEmpty() ? new LinkedHashMap<>(suggestionAttrs) : null
                );

                if (run.isText() && "\n".equals(run.getText())) {
                    attrs.put("review-block-base", basePayload);
                } else {
                    attrs.put("review-base", basePayload);
                }
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
                continue;
            }

            /*
             * Standalone inserted newline needs a visible virtual marker.
             * The marker is review-only content inserted into visualDelta.
             * The actual newline is still retained and owns the source references.
             */
            if (
                    mode == AttributionViewMode.REVIEW
                            && run.getNewlineSuggestion() != null
                            && run.isText()
                            && "\n".equals(run.getText())
                            && !isTerminalDocumentNewline(collapsed, run)
                            && (
                            run.getNewlineSuggestion().getType() == null
                                    || run.getNewlineSuggestion().getType() == NewlineSuggestionType.STANDALONE
                    )
            ) {
                Map<String, Object> markerAttrs = new LinkedHashMap<>();

                markerAttrs.put(
                        "suggestion-newline",
                        buildNewlineSuggestionPayload(run, true)
                );

                boolean startsDocument = run.getLogicalStart() == 0;

                if (startsDocument) {
                    /*
                     * Leading standalone newline:
                     * marker comes before because there is no previous visual line.
                     */
                    delta.insert(" ↵ ", markerAttrs);
                    delta.retain(run.length(), attrs.isEmpty() ? null : attrs);
                } else {
                    /*
                     * Normal standalone newline:
                     * retain the real newline first, then place the marker after it,
                     * so the marker appears on the blank line created by the newline.
                     */
                    delta.retain(run.length(), attrs.isEmpty() ? null : attrs);
                    delta.insert(" ↵ ", markerAttrs);
                }

                continue;
            }

            delta.retain(run.length(), attrs.isEmpty() ? null : attrs);
        }

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

    private Map<String, Object> buildNewlineSuggestionPayload(
            ReviewRun run,
            boolean marker
    ) {
        NewlineSuggestion suggestion = run.getNewlineSuggestion();

        Map<String, Object> baseAttrs =
                run.getBaseAttributes() != null
                        ? run.getBaseAttributes()
                        : Collections.emptyMap();

        Map<String, Object> suggestionAttrs =
                run.getSuggestionAttributes() != null
                        ? run.getSuggestionAttributes()
                        : Collections.emptyMap();

        Map<String, Object> payload = new LinkedHashMap<>();

        payload.put("groupId", suggestion.getGroupId());
        payload.put("actorEmail", suggestion.getActorEmail());
        payload.put("createdAt", suggestion.getCreatedAt());

        /*
         * The virtual marker must not own source refs.
         * The real newline owns the refs so accept/reject is recorded once.
         */
        payload.put(
                "references",
                marker
                        ? new ArrayList<>()
                        : cloneSuggestionReferences(run.getReferences())
        );

        payload.put(
                "dependsOnReviewRunIds",
                suggestion.getDependsOnReviewRunIds() != null
                        ? new ArrayList<>(suggestion.getDependsOnReviewRunIds())
                        : new ArrayList<>()
        );

        payload.put(
                "type",
                suggestion.getType() != null
                        ? suggestion.getType()
                        : NewlineSuggestionType.STANDALONE
        );

        payload.put("marker", marker);

        payload.put(
                "baseAttributes",
                !baseAttrs.isEmpty() ? new LinkedHashMap<>(baseAttrs) : null
        );

        payload.put(
                "suggestionAttributes",
                !suggestionAttrs.isEmpty() ? new LinkedHashMap<>(suggestionAttrs) : null
        );

        return payload;
    }

    private boolean isTerminalDocumentNewline(
            List<ReviewRun> runs,
            ReviewRun candidate
    ) {
        if (candidate == null || !candidate.isText() || !"\n".equals(candidate.getText())) {
            return false;
        }

        for (int i = runs.size() - 1; i >= 0; i--) {
            ReviewRun run = runs.get(i);

            if (run == null || run.length() <= 0) {
                continue;
            }

            return run == candidate;
        }

        return false;
    }

    private void attachStandaloneNewlineDependenciesToBlockSuggestions(
            List<ReviewRun> runs,
            List<BlockFormatSuggestionItem> blockFormatSuggestions
    ) {
        if (runs == null || runs.isEmpty()) return;
        if (blockFormatSuggestions == null || blockFormatSuggestions.isEmpty()) return;

        Map<Integer, String> standaloneNewlineGroupByStart = new LinkedHashMap<>();

        for (ReviewRun run : runs) {
            if (!isNewlineRun(run)) continue;
            if (run.getNewlineSuggestion() == null) continue;

            NewlineSuggestion suggestion = run.getNewlineSuggestion();

            if (suggestion.getType() != NewlineSuggestionType.STANDALONE) {
                continue;
            }

            if (suggestion.getGroupId() == null || suggestion.getGroupId().isBlank()) {
                continue;
            }

            standaloneNewlineGroupByStart.put(
                    run.getLogicalStart(),
                    suggestion.getGroupId()
            );
        }

        if (standaloneNewlineGroupByStart.isEmpty()) return;

        for (BlockFormatSuggestionItem item : blockFormatSuggestions) {
            if (item.getReferences() == null || item.getReferences().isEmpty()) {
                continue;
            }

            for (Reference ref : item.getReferences()) {
                int start = ref.getReviewStart();

                addNearbyStandaloneNewlineDependency(
                        item,
                        standaloneNewlineGroupByStart,
                        start
                );
            }
        }
    }

    private void addNearbyStandaloneNewlineDependency(
            BlockFormatSuggestionItem item,
            Map<Integer, String> standaloneNewlineGroupByStart,
            int blockRefStart
    ) {
        addNewlineDependency(item, standaloneNewlineGroupByStart.get(blockRefStart - 1));
        addNewlineDependency(item, standaloneNewlineGroupByStart.get(blockRefStart));
        addNewlineDependency(item, standaloneNewlineGroupByStart.get(blockRefStart + 1));
    }

    private void addNewlineDependency(
            BlockFormatSuggestionItem item,
            String newlineGroupId
    ) {
        if (item == null || newlineGroupId == null || newlineGroupId.isBlank()) {
            return;
        }

        if (item.getDependsOnNewlineGroupIds() == null) {
            item.setDependsOnNewlineGroupIds(new ArrayList<>());
        }

        if (!item.getDependsOnNewlineGroupIds().contains(newlineGroupId)) {
            item.getDependsOnNewlineGroupIds().add(newlineGroupId);
        }
    }
}