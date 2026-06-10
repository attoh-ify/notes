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
        List<ChangeSegment> runs = new ArrayList<>();
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
                                ChangeSegment.builder()
                                        .id(reviewRunIdForBase(seedPos))
                                        .text(parts[i])
                                        .baseAttributes(new LinkedHashMap<>(inlineAttrs))
                                        .changeAttributes(new LinkedHashMap<>())
                                        .references(new ArrayList<>())
                                        .logicalStart(seedPos)
                                        .build()
                        );
                        seedPos += parts[i].length();
                    }

                    if (i < parts.length - 1) {
                        runs.add(
                                ChangeSegment.builder()
                                        .id(reviewRunIdForBase(seedPos))
                                        .text("\n")
                                        .baseAttributes(new LinkedHashMap<>(blockAttrs))
                                        .changeAttributes(new LinkedHashMap<>())
                                        .references(new ArrayList<>())
                                        .logicalStart(seedPos)
                                        .build()
                        );
                        seedPos += 1;
                    }
                }
            }  else if (op.getInsert() instanceof Map<?, ?> embed) {
                runs.add(
                        ChangeSegment.builder()
                                .id(reviewRunIdForBase(seedPos))
                                .embed(cloneEmbed(embed))
                                .baseAttributes(new LinkedHashMap<>(onlyInlineAttrs(opAttrs)))
                                .changeAttributes(new LinkedHashMap<>())
                                .references(new ArrayList<>())
                                .logicalStart(seedPos)
                                .build()
                );

                seedPos += 1;
            }
        }

        // ── PHASE 3 ───────────────────────────────────────────────────────────

        List<FormatChangeItem> formatChanges = new ArrayList<>();
        List<BlockFormatChangeItem> blockFormatChanges = new ArrayList<>();
        AttributionCancellationAccumulator accumulator = new AttributionCancellationAccumulator();

        for (TextOperation textOp : changeTextOps) {
            String opId = textOp.getOpId();
            String authorEmail = textOp.getActorEmail();
            String createdAt = textOp.getCreatedAt().toString();

            int localLogPos = 0;
            InsertChange currentInsertGroup = null;
            DeleteChange currentDeleteGroup = null;
            FormatChangeItem currentFormatGroup = null;
            Map<BlockGroupKey, BlockFormatChangeItem> currentBlockGroups = new LinkedHashMap<>();

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

                // ── CASE B: retain with attributes (format) ────────
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
                            ChangeSegment run = runs.get(cursor);

                            if (run.getDeleteChange() != null) {
                                cursor++;
                                continue;
                            }

                            if (run.isText() && run.length() > remaining) {
                                splitAt(runs, cursor, remaining);
                            }

                            ChangeSegment target = runs.get(cursor);
                            int spanLen = target.length();
                            int componentStart = retainLen - remaining;

                            if (isBlockTargetRun(target)) {
                                for (Map.Entry<String, Object> entry : blockIncomingAttrs.entrySet()) {
                                    applyBlockAttributeToNewlineRun(
                                            runs,
                                            blockFormatChanges,
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
                            ChangeSegment run = runs.get(cursor);

                            if (run.getDeleteChange() != null || isBlockTargetRun(run)) {
                                cursor++;
                                continue;
                            }

                            if (run.isText() && run.length() > remaining) {
                                splitAt(runs, cursor, remaining);
                            }

                            ChangeSegment target = runs.get(cursor);
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

                                List<FormatChangeItem> coveringFormats = formatChanges.stream()
                                        .filter(f -> attrKey.equals(f.getAttributeKey()))
                                        .filter(f -> formatChangeCoversRange(f, spanStart, spanLen))
                                        .toList();

                                for (FormatChangeItem fmt : new ArrayList<>(coveringFormats)) {
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
                                                removeRangeFromReferencesWithoutShift(
                                                        fmt.getReferences(),
                                                        spanStart,
                                                        spanLen
                                                )
                                        );

                                        if (fmt.getReferences().isEmpty()) {
                                            formatChanges.remove(fmt);
                                        }

                                        if (target.getChangeAttributes() != null) {
                                            target.getChangeAttributes().remove(attrKey);
                                        }
                                    }
                                }

                                if (Objects.equals(baseValue, attrValue)) {
                                    continue;
                                }

                                Map<String, Object> attrsToOverlay = new HashMap<>();
                                attrsToOverlay.put(attrKey, attrValue);

                                target.setChangeAttributes(
                                        overlayAttrsPreserveNull(
                                                target.getChangeAttributes(),
                                                attrsToOverlay
                                        )
                                );

                                if (currentFormatGroup == null
                                        || !attrKey.equals(currentFormatGroup.getAttributeKey())
                                        || !Objects.equals(attrValue, currentFormatGroup.getAttributeValue())) {

                                    currentFormatGroup = findOrCreateCompatibleFormatChange(
                                            formatChanges,
                                            authorEmail,
                                            createdAt,
                                            attrKey,
                                            attrValue,
                                            spanStart,
                                            spanEnd
                                    );
                                }

                                if (target.getInsertChange() != null) {
                                    addInsertDependency(
                                            currentFormatGroup,
                                            target.getInsertChange().getGroupId()
                                    );
                                }

                                int componentStart = retainLen - remaining;

                                currentFormatGroup.setReferences(
                                        addReference(
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

                    ChangeSegment prevGroupingRun =
                            effectivePreviousRunForInsertGrouping(runs, insertAtIdx);

                    ChangeSegment nextGroupingRun =
                            effectiveNextRunForInsertGrouping(runs, insertAtIdx);

                    if (currentInsertGroup == null) {
                        InsertChange prevAdj =
                                compatibleAdjacentInsertChange(
                                        prevGroupingRun,
                                        authorEmail,
                                        insertKind
                                );

                        InsertChange nextAdj =
                                compatibleAdjacentInsertChange(
                                        nextGroupingRun,
                                        authorEmail,
                                        insertKind
                                );

                        InsertChange adj = prevAdj != null ? prevAdj : nextAdj;

                        currentInsertGroup = adj != null
                                ? copyInsertChange(adj)
                                : InsertChange.builder()
                                .groupId(nextId())
                                .actorEmail(authorEmail)
                                .createdAt(createdAt)
                                .build();

                    } else {
                        boolean currentGroupStillCompatible =
                                (prevGroupingRun != null
                                        && prevGroupingRun.getInsertChange() != null
                                        && currentInsertGroup.getGroupId().equals(
                                        prevGroupingRun.getInsertChange().getGroupId()
                                )
                                        && sameInsertContentKind(prevGroupingRun, insertKind))
                                        ||
                                        (nextGroupingRun != null
                                                && nextGroupingRun.getInsertChange() != null
                                                && currentInsertGroup.getGroupId().equals(
                                                nextGroupingRun.getInsertChange().getGroupId()
                                        )
                                                && sameInsertContentKind(nextGroupingRun, insertKind));

                        if (!currentGroupStillCompatible) {
                            currentInsertGroup = InsertChange.builder()
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

                    Map<String, Object> inheritedChangeAttrs= new LinkedHashMap<>();
                    Set<String> extendedGroupIds = new LinkedHashSet<>();

                    for (Iterator<Map.Entry<String, Object>> it = ownAttrs.entrySet().iterator(); it.hasNext(); ) {
                        Map.Entry<String, Object> entry = it.next();
                        String key = entry.getKey();
                        Object value = entry.getValue();
                        int finalLocalLogPos = localLogPos;

                        FormatChangeItem inheritedFormat = formatChanges.stream()
                                .filter(f -> key.equals(f.getAttributeKey()))
                                .filter(f -> Objects.equals(value, f.getAttributeValue()))
                                .filter(f -> formatChangeShouldInheritInsert(f, finalLocalLogPos))
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

                        inheritedChangeAttrs.put(key, value);
                        extendedGroupIds.add(inheritedFormat.getGroupId());
                        it.remove();
                    }

                    Map<String, Object> prevEffectiveAttrs = getEffectiveAttrs(prevGroupingRun);
                    Map<String, Object> nextEffectiveAttrs = getEffectiveAttrs(nextGroupingRun);

                    if (!ownAttrs.isEmpty()
                            && prevGroupingRun != null
                            && prevGroupingRun.getInsertChange() != null
                            && !authorEmail.equals(prevGroupingRun.getInsertChange().getActorEmail())
                            && !prevEffectiveAttrs.isEmpty()) {

                        Map<String, Object> inherited = intersectAttrs(ownAttrs, prevEffectiveAttrs);

                        if (!inherited.isEmpty()) {
                            InsertGroupCollection prevGroup = collectInsertGroupRunsWithAttrs(
                                    runs,
                                    prevGroupingRun.getInsertChange().getGroupId(),
                                    inherited
                            );

                            if (prevGroup != null) {
                                for (Map.Entry<String, Object> inheritedEntry : inherited.entrySet()) {
                                    String inheritedKey = inheritedEntry.getKey();
                                    Object inheritedValue = inheritedEntry.getValue();

                                    Map<String, Object> singleInherited =
                                            new LinkedHashMap<>(Map.of(inheritedKey, inheritedValue));

                                    FormatChangeItem g = findOrCreateFormatChangeByIdentity(
                                            formatChanges,
                                            prevGroupingRun.getInsertChange().getActorEmail(),
                                            prevGroupingRun.getInsertChange().getCreatedAt(),
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
                                            g.setReferences(appendAndCoalesceReference(g.getReferences(), ref));
                                        }
                                    }

                                    g.setReferences(addReference(
                                            g.getReferences(),
                                            localLogPos,
                                            0,
                                            shiftLen,
                                            opId,
                                            compIdx
                                    ));

                                    addInsertDependency(g, prevGroupingRun.getInsertChange().getGroupId());
                                    addInsertDependency(g, currentInsertGroup.getGroupId());
                                    extendedGroupIds.add(g.getGroupId());

                                    moveAttrsFromBaseToChangeForRuns(
                                            runs,
                                            prevGroup.indices(),
                                            singleInherited
                                    );

                                    inheritedChangeAttrs.put(inheritedKey, inheritedValue);
                                }

                                ownAttrs = subtractAttrs(ownAttrs, inherited);
                            }
                        }
                    }

                    if (!ownAttrs.isEmpty()
                            && nextGroupingRun != null
                            && nextGroupingRun.getInsertChange() != null
                            && !authorEmail.equals(nextGroupingRun.getInsertChange().getActorEmail())
                            && !nextEffectiveAttrs.isEmpty()) {

                        Map<String, Object> inherited = intersectAttrs(ownAttrs, nextEffectiveAttrs);

                        if (!inherited.isEmpty()) {
                            InsertGroupCollection nextGroup = collectInsertGroupRunsWithAttrs(
                                    runs,
                                    nextGroupingRun.getInsertChange().getGroupId(),
                                    inherited
                            );

                            if (nextGroup != null) {
                                for (Map.Entry<String, Object> inheritedEntry : inherited.entrySet()) {
                                    String inheritedKey = inheritedEntry.getKey();
                                    Object inheritedValue = inheritedEntry.getValue();

                                    Map<String, Object> singleInherited =
                                            new LinkedHashMap<>(Map.of(inheritedKey, inheritedValue));

                                    FormatChangeItem g = findOrCreateFormatChangeByIdentity(
                                            formatChanges,
                                            nextGroupingRun.getInsertChange().getActorEmail(),
                                            nextGroupingRun.getInsertChange().getCreatedAt(),
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
                                            g.setReferences(appendAndCoalesceReference(g.getReferences(), ref));
                                        }
                                    }

                                    g.setReferences(addReference(
                                            g.getReferences(),
                                            localLogPos,
                                            0,
                                            shiftLen,
                                            opId,
                                            compIdx
                                    ));

                                    addInsertDependency(g, nextGroupingRun.getInsertChange().getGroupId());
                                    addInsertDependency(g, currentInsertGroup.getGroupId());
                                    extendedGroupIds.add(g.getGroupId());

                                    moveAttrsFromBaseToChangeForRuns(
                                            runs,
                                            nextGroup.indices(),
                                            singleInherited
                                    );

                                    inheritedChangeAttrs.put(inheritedKey, inheritedValue);
                                }

                                ownAttrs = subtractAttrs(ownAttrs, inherited);
                            }
                        }
                    }

                    shiftChangeReferenceReviewStarts(
                            runs,
                            insertAbsPos,
                            shiftLen,
                            currentInsertGroup.getGroupId()
                    );

                    shiftFormatChangeReferences(
                            formatChanges,
                            insertAbsPos,
                            shiftLen,
                            extendedGroupIds
                    );

                    shiftBlockFormatChangeReferences(
                            blockFormatChanges,
                            insertAbsPos,
                            shiftLen,
                            Collections.emptySet()
                    );

                    int spliceAt = insertAtIdx;
                    int runPos = insertAbsPos;

                    for (InsertFragment fragment : fragments) {
                        List<Reference> runRefs = addReference(
                                new ArrayList<>(),
                                runPos,
                                fragment.componentStart(),
                                fragment.length(),
                                opId,
                                compIdx
                        );

                        Map<String, Object> fragmentBaseAttributes = fragment.newline()
                                ? new LinkedHashMap<>()
                                : new LinkedHashMap<>(onlyInlineAttrs(ownAttrs));

                        Map<String, Object> fragmentChangeAttributes = fragment.newline()
                                ? new LinkedHashMap<>(onlyBlockAttrs(component.getAttributes()))
                                : new LinkedHashMap<>(inheritedChangeAttrs);

                        ChangeSegment.ChangeSegmentBuilder builder = ChangeSegment.builder()
                                .id(reviewRunIdForReference(opId, compIdx, fragment.componentStart(), runPos))
                                .baseAttributes(fragmentBaseAttributes)
                                .changeAttributes(fragmentChangeAttributes)
                                .references(runRefs)
                                .logicalStart(runPos)
                                .insertChange(copyInsertChange(currentInsertGroup));

                        if (fragment.newline()) {
                            builder.text("\n");
                        } else if (fragment.isEmbed()) {
                            builder.embed(fragment.embed());
                        } else {
                            builder.text(fragment.text());
                        }

                        ChangeSegment newRun = builder.build();

                        runs.add(spliceAt++, newRun);

                        if (fragment.newline()) {
                            Map<String, Object> insertedBlockAttrs = onlyBlockAttrs(component.getAttributes());

                            for (Map.Entry<String, Object> entry : insertedBlockAttrs.entrySet()) {
                                applyBlockAttributeToNewlineRun(
                                        runs,
                                        blockFormatChanges,
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
                        ChangeSegment prevDeleteGroupingRun =
                                effectivePreviousRunForDeleteGrouping(runs, cursor);

                        ChangeSegment nextDeleteGroupingRun =
                                effectiveNextRunForDeleteGrouping(runs, cursor);

                        DeleteChange prevAdj =
                                prevDeleteGroupingRun != null
                                        && prevDeleteGroupingRun.getDeleteChange() != null
                                        && authorEmail.equals(prevDeleteGroupingRun.getDeleteChange().getActorEmail())
                                        ? prevDeleteGroupingRun.getDeleteChange()
                                        : null;

                        DeleteChange nextAdj =
                                nextDeleteGroupingRun != null
                                        && nextDeleteGroupingRun.getDeleteChange() != null
                                        && authorEmail.equals(nextDeleteGroupingRun.getDeleteChange().getActorEmail())
                                        ? nextDeleteGroupingRun.getDeleteChange()
                                        : null;

                        if (prevAdj != null) {
                            currentDeleteGroup = copyDeleteChange(prevAdj);

                            if (nextAdj != null && !nextAdj.getGroupId().equals(prevAdj.getGroupId())) {
                                if (nextAdj.getCreatedAt().compareTo(currentDeleteGroup.getCreatedAt()) > 0) {
                                    currentDeleteGroup.setCreatedAt(nextAdj.getCreatedAt());
                                }

                                for (ChangeSegment existingRun : runs) {
                                    if (existingRun.getDeleteChange() != null
                                            && nextAdj.getGroupId().equals(existingRun.getDeleteChange().getGroupId())) {
                                        existingRun.setDeleteChange(copyDeleteChange(currentDeleteGroup));
                                    }
                                }
                            }
                        } else if (nextAdj != null) {
                            currentDeleteGroup = copyDeleteChange(nextAdj);
                        } else {
                            currentDeleteGroup = DeleteChange.builder()
                                    .groupId(nextId())
                                    .actorEmail(authorEmail)
                                    .createdAt(createdAt)
                                    .type(DeleteChange.DeleteChangeType.TEXT)
                                    .build();
                        }
                    } else if (createdAt.compareTo(currentDeleteGroup.getCreatedAt()) > 0) {
                        currentDeleteGroup.setCreatedAt(createdAt);
                    }

                    int remaining = component.getDelete();
                    int deleteComponentLength = component.getDelete();

                    while (remaining > 0 && cursor < runs.size()) {
                        ChangeSegment run = runs.get(cursor);
                        int deleteComponentLocalStart = deleteComponentLength - remaining;

                        if (run.getDeleteChange() != null) {
                            cursor++;
                            continue;
                        }

                        if (run.isText() && remaining < run.length()) {
                            splitAt(runs, cursor, remaining);
                        }

                        ChangeSegment target = runs.get(cursor);
                        int len = target.length();

                        if (target.getInsertChange() != null) {
                            int runStart = target.getLogicalStart();
                            int runEnd = runStart + target.length();

                            List<Reference> targetReferences = target.getReferences();

                            int deleteStartPos = target.getLogicalStart();
                            int deleteLen = target.length();
                            int deleteEndPos = deleteStartPos + deleteLen;

                            if (isBlockTargetRun(target)) {
                                cancelBlockChangesForDeletedNewline(
                                        blockFormatChanges,
                                        accumulator,
                                        target
                                );
                            }

                            runs.remove(cursor);

                            for (int i = cursor; i < runs.size(); i++) {
                                runs.get(i).setLogicalStart(runs.get(i).getLogicalStart() - deleteLen);
                            }

                            deleteRangeFromRunReferencesAndShift(runs, deleteStartPos, deleteLen);
                            deleteRangeFromFormatChangeReferencesAndShift(formatChanges, deleteStartPos, deleteLen);

                            deleteRangeFromBlockFormatChangeReferencesAndShift(
                                    blockFormatChanges,
                                    deleteStartPos,
                                    deleteLen
                            );

                            formatChanges.removeIf(fmt ->
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

                            Iterator<FormatChangeItem> fmtIt = formatChanges.iterator();

                            while (fmtIt.hasNext()) {
                                FormatChangeItem fmt = fmtIt.next();

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
                                            deleteRangeFromReferencesAndShift(
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
                                cancelBlockChangesForDeletedNewline(
                                        blockFormatChanges,
                                        accumulator,
                                        target
                                );
                            }

                            DeleteChange.DeleteChangeType nextType =
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

                            DeleteChange runDelete = copyDeleteChange(currentDeleteGroup);

                            target.setReferences(
                                    addReference(
                                            new ArrayList<>(),
                                            target.getLogicalStart(),
                                            deleteComponentLocalStart,
                                            len,
                                            opId,
                                            compIdx
                                    )
                            );

                            target.setDeleteChange(runDelete);

                            int deleteStartPos = target.getLogicalStart();
                            int deleteEndPos = deleteStartPos + len;

                            for (FormatChangeItem fmt : formatChanges) {
                                if (formatChangeOverlapsRange(fmt, deleteStartPos, deleteEndPos)) {
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
        formatChanges.removeIf(fmt -> fmt.getReferences() == null || fmt.getReferences().isEmpty());

        blockFormatChanges.removeIf(fmt ->
                fmt.getReferences() == null || fmt.getReferences().isEmpty()
        );

        // ── PHASE 5 ───────────────────────────────────────────────────────────

        for (FormatChangeItem fmt : formatChanges) {
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
                            .filter(r -> r.getDeleteChange() == null)
                            .anyMatch(r -> {
                                int rs = r.getLogicalStart();
                                int re = rs + r.length();
                                return re > finalPrevSpanEnd && rs < spanStart && "\n".equals(r.getText());
                            });
                    texts.append(sawNewlineGap ? " ↵ " : " ... ");
                }

                for (ChangeSegment run : runs) {
                    if (run.getDeleteChange() != null) continue;
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

        for (BlockFormatChangeItem fmt : blockFormatChanges) {
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
        normalizeContinuingBlockFormatGroups(
                runs,
                blockFormatChanges
        );

        syncBlockFormatDependenciesFromTargetNewlines(
                runs,
                blockFormatChanges
        );

        Delta visualDelta = buildVisualDelta(runs, mode);

        for (TextOperation textOp : changeTextOps) {
            baseDelta = baseDelta.compose(new Delta(textOp.getDelta().ops));
        }
        baseDelta = QuillDeltaUtils.ensureTerminalNewline(baseDelta);

        AuditProjection projection = new AuditProjection(
                baseDelta,
                visualDelta,
                formatChanges,
                blockFormatChanges
        );

        return new AttributionBuildResult(projection, revisionLogChanged);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static FormatKeyChangeType getFormatKeyChangeType(
            FormatChangeItem fmt,
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

    private Delta buildVisualDelta(List<ChangeSegment> runs, AttributionViewMode mode) {
        List<ChangeSegment> collapsed = new ArrayList<>();

        for (ChangeSegment run : runs) {
            if (run == null || run.length() <= 0) {
                continue;
            }

            ChangeSegment last = collapsed.isEmpty() ? null : collapsed.get(collapsed.size() - 1);

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
                            last.getInsertChange() != null
                                    ? last.getInsertChange().getGroupId()
                                    : null,
                            run.getInsertChange() != null
                                    ? run.getInsertChange().getGroupId()
                                    : null
                    )
                            && Objects.equals(
                            last.getDeleteChange() != null
                                    ? last.getDeleteChange().getGroupId()
                                    : null,
                            run.getDeleteChange() != null
                                    ? run.getDeleteChange().getGroupId()
                                    : null
                    )
                            && Objects.equals(
                            last.getDeleteChange() != null
                                    ? last.getDeleteChange().getType()
                                    : null,
                            run.getDeleteChange() != null
                                    ? run.getDeleteChange().getType()
                                    : null
                    )
                            && (last.getInsertChange() == null) == (run.getInsertChange() == null)
                            && (last.getDeleteChange() == null) == (run.getDeleteChange() == null)
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
                        appendReferences(
                                last.getReferences(),
                                run.getReferences()
                        )
                );

                continue;
            }

            ChangeSegment.ChangeSegmentBuilder builder = ChangeSegment.builder()
                    .baseAttributes(new LinkedHashMap<>(
                            run.getBaseAttributes() != null
                                    ? run.getBaseAttributes()
                                    : Collections.emptyMap()
                    ))
                    .changeAttributes(new LinkedHashMap<>(
                            run.getChangeAttributes() != null
                                    ? run.getChangeAttributes()
                                    : Collections.emptyMap()
                    ))
                    .references(cloneReferences(run.getReferences()))
                    .logicalStart(run.getLogicalStart())
                    .insertChange(
                            run.getInsertChange() != null
                                    ? copyInsertChange(run.getInsertChange())
                                    : null
                    )
                    .deleteChange(
                            run.getDeleteChange() != null
                                    ? copyDeleteChange(run.getDeleteChange())
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

        for (ChangeSegment run : collapsed) {
            if (run == null || run.length() <= 0) {
                continue;
            }

            Map<String, Object> baseAttrs =
                    run.getBaseAttributes() != null
                            ? run.getBaseAttributes()
                            : Collections.emptyMap();

            Map<String, Object> changeAttrs =
                    run.getChangeAttributes() != null
                            ? run.getChangeAttributes()
                            : Collections.emptyMap();

            boolean isInsertedPending =
                    run.getInsertChange() != null;

            boolean isDeletedPending =
                    run.getDeleteChange() != null && !isInsertedPending;

            boolean isDeletedNewline =
                    run.isText()
                            && "\n".equals(run.getText())
                            && run.getDeleteChange() != null;

            Map<String, Object> attrs = new LinkedHashMap<>();

            if (!baseAttrs.isEmpty()) {
                attrs.putAll(baseAttrs);
            }

            if (!changeAttrs.isEmpty()) {
                attrs.putAll(changeAttrs);
            }

            if (run.getInsertChange() != null) {
                Map<String, Object> insertPayload = new LinkedHashMap<>();
                insertPayload.put("groupId", run.getInsertChange().getGroupId());
                insertPayload.put("actorEmail", run.getInsertChange().getActorEmail());
                insertPayload.put("createdAt", run.getInsertChange().getCreatedAt());
                insertPayload.put("references", run.getReferences());
                insertPayload.put("baseAttributes", !baseAttrs.isEmpty() ? baseAttrs : null);
                insertPayload.put(
                        "changeAttributes",
                        !changeAttrs.isEmpty() ? changeAttrs : null
                );

                attrs.put("audit-insert", insertPayload);
            }

            if (run.getDeleteChange() != null) {
                Map<String, Object> deletePayload = new LinkedHashMap<>();
                deletePayload.put("groupId", run.getDeleteChange().getGroupId());
                deletePayload.put("actorEmail", run.getDeleteChange().getActorEmail());
                deletePayload.put("createdAt", run.getDeleteChange().getCreatedAt());
                deletePayload.put("references", run.getReferences());
                deletePayload.put("baseAttributes", !baseAttrs.isEmpty() ? baseAttrs : null);
                deletePayload.put(
                        "changeAttributes",
                        !changeAttrs.isEmpty() ? changeAttrs : null
                );
                deletePayload.put("type", run.getDeleteChange().getType());

                DeleteChange.DeleteChangeType type =
                        run.getDeleteChange().getType() != null
                                ? run.getDeleteChange().getType()
                                : DeleteChange.DeleteChangeType.TEXT;

                if (type == DeleteChange.DeleteChangeType.SINGLE_LINE) {
                    attrs.put("audit-delete-singleline", deletePayload);
                } else if (type == DeleteChange.DeleteChangeType.MULTI_LINE) {
                    attrs.put("audit-delete-multiline", deletePayload);
                } else {
                    attrs.put("audit-delete", deletePayload);
                }
            }

            boolean isPlainBaseReviewRun =
                    run.getInsertChange() == null
                            && run.getDeleteChange() == null;

            if (isPlainBaseReviewRun) {
                Map<String, Object> basePayload = new LinkedHashMap<>();

                basePayload.put(
                        "baseAttributes",
                        !baseAttrs.isEmpty() ? new LinkedHashMap<>(baseAttrs) : null
                );

                basePayload.put(
                        "changeAttributes",
                        !changeAttrs.isEmpty() ? new LinkedHashMap<>(changeAttrs) : null
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
                    DeleteChange.DeleteChangeType type =
                            run.getDeleteChange().getType() != null
                                    ? run.getDeleteChange().getType()
                                    : DeleteChange.DeleteChangeType.TEXT;

                    insertValue = type == DeleteChange.DeleteChangeType.SINGLE_LINE
                            ? " ↵ "
                            : run.getText();
                } else {
                    insertValue = run.getText();
                }

                delta.insert(insertValue, attrs.isEmpty() ? null : attrs);
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

    private static InsertContentKind kindOfRun(ChangeSegment run) {
        if (run == null) return null;
        return run.isEmbed() ? InsertContentKind.EMBED : InsertContentKind.TEXT;
    }

    private static InsertContentKind kindOfFragment(InsertFragment fragment) {
        if (fragment == null) return null;
        return fragment.isEmbed() ? InsertContentKind.EMBED : InsertContentKind.TEXT;
    }

    private static boolean sameInsertContentKind(
            ChangeSegment run,
            InsertContentKind kind
    ) {
        if (run == null || kind == null) return false;
        return kindOfRun(run) == kind;
    }

    private static InsertChange compatibleAdjacentInsertChange(
            ChangeSegment run,
            String authorEmail,
            InsertContentKind insertKind
    ) {
        if (run == null) return null;
        if (run.getInsertChange() == null) return null;
        if (!authorEmail.equals(run.getInsertChange().getActorEmail())) return null;
        if (!sameInsertContentKind(run, insertKind)) return null;

        return run.getInsertChange();
    }
}