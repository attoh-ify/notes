package com.example.notes.services.impl;

import com.example.notes.dto.attribution.*;
import com.example.notes.dto.note.NoteDto;
import com.example.notes.dto.noteVersion.NoteVersionDto;
import com.example.notes.dto.ot.Delta;
import com.example.notes.dto.ot.Op;
import com.example.notes.dto.ot.TextOperation;
import com.example.notes.services.AttributionService;
import com.example.notes.services.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

import static com.example.notes.utils.AttributionHelpers.*;

@Service
public class AttributionServiceImpl implements AttributionService {
    private final RedisService redisService;
    private final NotePersistenceService notePersistenceService;

    private static final Logger log =
            LoggerFactory.getLogger(AttributionServiceImpl.class);

    public AttributionServiceImpl(RedisService redisService, NotePersistenceService notePersistenceService) {
        this.redisService = redisService;
        this.notePersistenceService = notePersistenceService;
    }

    @Override
    public ReviewProjection buildReviewProjection(
            String actorEmail, UUID noteId, List<TextOperation> baseTextOps, List<TextOperation> changeTextOps
    ) {
        resetGroupCounter();

        Delta baseDelta = new Delta();
        for (TextOperation textOp : baseTextOps) {
            baseDelta = baseDelta.compose(new Delta(textOp.getDelta().ops));
        }

        List<ReviewRun> runs = new ArrayList<>();
        int seedPos = 0;

        for (Op op : baseDelta.ops) {
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


        List<FormatSuggestionItem> formatSuggestions = new ArrayList<>();
        CancellationAccumulator accumulator = new CancellationAccumulator();

        for (TextOperation textOp : changeTextOps) {
            String opId = textOp.getOpId();
            String authorEmail = textOp.getActorEmail();
            String createdAt = textOp.getCreatedAt().toString();

            int localLogPos = 0;
            InsertSuggestion currentInsertGroup = null;
            DeleteSuggestion currentDeleteGroup = null;
            FormatSuggestionItem currentFormatGroup = null;

            List<Op> components = textOp.getDelta().ops;
            for (int compIdx = 0; compIdx < components.size(); compIdx++) {
                Op component = components.get(compIdx);

                if (component.isRetain() && component.getAttributes() == null) {
                    boolean isLastOp = (compIdx == components.size() - 1);

                    if (isLastOp) break;

                    currentInsertGroup = null;
                    currentDeleteGroup = null;

                    int retainLen = (int) component.getRetain();
                    boolean newlineOnly = isOnlyNewlineRetain(runs, localLogPos, retainLen);

                    if (!newlineOnly || currentFormatGroup == null) {
                        currentFormatGroup = null;
                    }

                    localLogPos += retainLen;

                } else if (component.isRetain() && component.getAttributes() != null) {
                    currentInsertGroup = null;
                    currentDeleteGroup = null;

                    int retainLen = (int) component.getRetain();

                    RunPosition startPos = findRunPos(runs, localLogPos);
                    int runIdx = startPos.idx();
                    int startOffset = startPos.offset();

                    if (startOffset > 0 && runIdx < runs.size()) {
                        runIdx = splitAt(runs, runIdx, startOffset);
                    }

                    int remaining = retainLen;
                    int cursor = runIdx;

                    Map<String, Object> rawIncomingAttrs = new LinkedHashMap<>(component.getAttributes());

                    while (remaining > 0 && cursor < runs.size()) {
                        ReviewRun run = runs.get(cursor);

                        if (run.getDeleteSuggestion() != null || "\n".equals(run.getText())) {
                            cursor++;
                            continue;
                        }

                        if (run.getText().length() > remaining) {
                            splitAt(runs, cursor, remaining);
                        }

                        ReviewRun target = runs.get(cursor);
                        int spanStart = target.getLogicalStart();
                        int spanLen = target.getText().length();

                        Map<String, Object> baseAttrs = target.getBaseAttributes() != null
                                ? target.getBaseAttributes() : Collections.emptyMap();

                        for (Map.Entry<String, Object> entry : rawIncomingAttrs.entrySet()) {
                            String attrKey = entry.getKey();
                            Object attrValue = entry.getValue();
                            Object baseValue = baseAttrs.get(attrKey);

                            List<FormatSuggestionItem> coveringFormats = formatSuggestions.stream()
                                    .filter(f -> attrKey.equals(f.getAttributeKey()))
                                    .filter(f -> f.getSpans().stream().anyMatch(s ->
                                            s.getStart() <= spanStart &&
                                            s.getStart() + s.getLength() >= spanStart + spanLen))
                                    .toList();

                            for (FormatSuggestionItem fmt : new ArrayList<>(coveringFormats)) {
                                FormatKeyChangeType type = getFormatKeyChangeType(fmt, attrValue, baseValue);

                                if (type == FormatKeyChangeType.CANCEL || type == FormatKeyChangeType.REPLACE) {
                                    int componentStart = retainLen - remaining;

                                    for (SuggestionSlice slice : fmt.getReferences()) {
                                        int sliceStart = slice.getComponentStart();
                                        int sliceEnd = sliceStart + slice.getLength();

                                        int overlapStart = Math.max(componentStart, sliceStart);
                                        int overlapEnd = Math.min(componentStart + spanLen, sliceEnd);

                                        if (overlapStart < overlapEnd) {
                                            accumulator.recordFormatCancellation(
                                                    slice.getRef().opId(),
                                                    slice.getRef().componentIndex(),
                                                    attrKey,
                                                    overlapStart,
                                                    overlapEnd - overlapStart
                                            );
                                        }
                                    }

                                    removeRangeFromFormatSuggestion(fmt, spanStart, spanLen);

                                    if (fmt.getSpans().isEmpty()) {
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

                            // Pending format attrs go into suggestionAttributes, never baseAttributes.
                            target.setSuggestionAttributes(
                                    overlayAttrsPreserveNull(
                                            target.getSuggestionAttributes(),
                                            Map.of(attrKey, attrValue)
                                    )
                            );

                            if (currentFormatGroup == null) {
                                FormatSuggestionItem prevAdj = formatSuggestions.stream()
                                        .filter(f -> f.getActorEmail().equals(authorEmail))
                                        .filter(f -> attrKey.equals(f.getAttributeKey()))
                                        .filter(f -> Objects.equals(attrValue, f.getAttributeValue()))
                                        .filter(f -> f.getSpans().stream().anyMatch(s -> s.getStart() + s.getLength() == spanStart))
                                        .findFirst()
                                        .orElse(null);

                                FormatSuggestionItem nextAdj = formatSuggestions.stream()
                                        .filter(f -> f.getActorEmail().equals(authorEmail))
                                        .filter(f -> attrKey.equals(f.getAttributeKey()))
                                        .filter(f -> Objects.equals(attrValue, f.getAttributeValue()))
                                        .filter(f -> f.getSpans().stream().anyMatch(s -> s.getStart() == spanStart + spanLen))
                                        .findFirst()
                                        .orElse(null);

                                FormatSuggestionItem existing = null;

                                if (prevAdj != null) {
                                    existing = prevAdj;

                                    if (nextAdj != null && !nextAdj.getGroupId().equals(prevAdj.getGroupId())) {
                                        existing.setReferences(appendSuggestionSlices(
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
                                        existing.setSpans(mergedSpans);

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
                                }

                                if (existing == null) {
                                    existing = FormatSuggestionItem.builder()
                                            .groupId(nextId())
                                            .actorEmail(authorEmail)
                                            .createdAt(createdAt)
                                            .attributeKey(attrKey)
                                            .attributeValue(attrValue)
                                            .references(new ArrayList<>())
                                            .spans(new ArrayList<>())
                                            .previewText("")
                                            .dependsOnInsertGroupIds(new ArrayList<>())
                                            .dependsOnDeleteGroupIds(new ArrayList<>())
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

                            int componentStart = retainLen - remaining;

                            currentFormatGroup.setReferences(addSuggestionSlice(
                                    currentFormatGroup.getReferences(),
                                    spanStart,        // reviewStart
                                    componentStart,   // componentStart
                                    spanLen,
                                    opId,
                                    compIdx
                            ));

                            int adjacentIdx = findAdjacentSpanIndex(currentFormatGroup.getSpans(), spanStart);
                            if (adjacentIdx != -1) {
                                currentFormatGroup.getSpans().get(adjacentIdx)
                                        .setLength(currentFormatGroup.getSpans().get(adjacentIdx).getLength() + spanLen);
                            } else {
                                currentFormatGroup.getSpans().add(
                                        FormatSuggestionSpan.builder().start(spanStart).length(spanLen).build());
                            }
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
                                    .build();
                        }

                    } else if (createdAt.compareTo(currentInsertGroup.getCreatedAt()) > 0) {
                        currentInsertGroup.setCreatedAt(createdAt);
                    }

                    Map<String, Object> ownAttrs = new LinkedHashMap<>(rawAttrs);
                    Set<String> extendedGroupIds = new LinkedHashSet<>();

                    for (Iterator<Map.Entry<String, Object>> it = ownAttrs.entrySet().iterator(); it.hasNext();) {

                        Map.Entry<String, Object> entry = it.next();
                        String key = entry.getKey();
                        Object value = entry.getValue();

                        int finalLocalLogPos = localLogPos;

                        FormatSuggestionItem existingAdj = formatSuggestions.stream()
                                .filter(f -> key.equals(f.getAttributeKey()))
                                .filter(f -> Objects.equals(value, f.getAttributeValue()))
                                .filter(f -> f.getSpans().stream()
                                        .anyMatch(s -> s.getStart() + s.getLength() == finalLocalLogPos))
                                .findFirst()
                                .orElse(null);

                        if (existingAdj == null) continue;

                        extendFormatGroupAtBoundary(
                                existingAdj,
                                localLogPos,
                                insertText.length(),
                                opId,
                                compIdx,
                                currentInsertGroup.getGroupId()
                        );

                        extendedGroupIds.add(existingAdj.getGroupId());
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

                            InsertGroupCollection prevGroup =
                                    collectInsertGroupRunsWithAttrs(
                                            runs,
                                            prevRun.getInsertSuggestion().getGroupId(),
                                            inherited
                                    );

                            if (prevGroup != null) {

                                FormatSuggestionItem g = FormatSuggestionItem.builder()
                                        .groupId(nextId())
                                        .actorEmail(prevRun.getInsertSuggestion().getActorEmail())
                                        .createdAt(prevRun.getInsertSuggestion().getCreatedAt())
                                        .attributeKey(attrsToJson(inherited))
                                        .attributeValue(inherited)
                                        .references(new ArrayList<>())
                                        .spans(List.of(
                                                FormatSuggestionSpan.builder()
                                                        .start(prevGroup.start)
                                                        .length((localLogPos + insertText.length()) - prevGroup.start)
                                                        .build()
                                        ))
                                        .dependsOnInsertGroupIds(new ArrayList<>(List.of(
                                                prevRun.getInsertSuggestion().getGroupId(),
                                                currentInsertGroup.getGroupId()
                                        )))
                                        .build();

                                g.setReferences(appendSuggestionSlices(
                                        g.getReferences(),
                                        collectReferencesForRunIndices(runs, prevGroup.indices)
                                ));

                                g.setReferences(addSuggestionSlice(
                                        g.getReferences(),
                                        localLogPos,
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

                    if (!ownAttrs.isEmpty()
                            && nextRun != null
                            && nextRun.getInsertSuggestion() != null
                            && !authorEmail.equals(nextRun.getInsertSuggestion().getActorEmail())
                            && !nextEffectiveAttrs.isEmpty()) {

                        Map<String, Object> inherited = intersectAttrs(ownAttrs, nextEffectiveAttrs);

                        if (!inherited.isEmpty()) {

                            InsertGroupCollection nextGroup =
                                    collectInsertGroupRunsWithAttrs(
                                            runs,
                                            nextRun.getInsertSuggestion().getGroupId(),
                                            inherited
                                    );

                            if (nextGroup != null) {

                                FormatSuggestionItem g = FormatSuggestionItem.builder()
                                        .groupId(nextId())
                                        .actorEmail(nextRun.getInsertSuggestion().getActorEmail())
                                        .createdAt(nextRun.getInsertSuggestion().getCreatedAt())
                                        .attributeKey(attrsToJson(inherited))
                                        .attributeValue(inherited)
                                        .references(collectReferencesForRunIndices(runs, nextGroup.indices))
                                        .spans(List.of(
                                                FormatSuggestionSpan.builder()
                                                        .start(localLogPos)
                                                        .length(nextGroup.end - localLogPos)
                                                        .build()
                                        ))
                                        .dependsOnInsertGroupIds(new ArrayList<>(List.of(
                                                nextRun.getInsertSuggestion().getGroupId(),
                                                currentInsertGroup.getGroupId()
                                        )))
                                        .build();

                                g.setReferences(addSuggestionSlice(
                                        g.getReferences(),
                                        localLogPos,
                                        0,
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

                    int shiftLen = insertText.length();

                    /*
                     * SHIFT EXISTING REFERENCES FIRST
                     * BEFORE inserting new runs
                     */
                    shiftSuggestionSliceReviewStarts(
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

                    shiftFormatSpansForInsert(
                            formatSuggestions,
                            insertAbsPos,
                            shiftLen,
                            extendedGroupIds
                    );

                    /*
                     * NOW insert the new runs
                     */
                    int componentLocalInsertCursor = 0;
                    String[] parts = insertText.split("\n", -1);
                    int spliceAt = insertAtIdx;
                    int runPos = insertAbsPos;

                    for (int i = 0; i < parts.length; i++) {

                        if (!parts[i].isEmpty()) {
                            InsertSuggestion runSuggestion = copyInsertSuggestion(currentInsertGroup);

                            List<SuggestionSlice> runRefs = addSuggestionSlice(
                                    new ArrayList<>(),
                                    runPos,
                                    componentLocalInsertCursor,
                                    parts[i].length(),
                                    opId,
                                    compIdx
                            );

                            ReviewRun newRun = ReviewRun.builder()
                                    .text(parts[i])
                                    .baseAttributes(new LinkedHashMap<>(ownAttrs))
                                    .suggestionAttributes(new LinkedHashMap<>())
                                    .references(runRefs)
                                    .logicalStart(runPos)
                                    .insertSuggestion(runSuggestion)
                                    .build();

                            runs.add(spliceAt++, newRun);

                            componentLocalInsertCursor += parts[i].length();
                            runPos += parts[i].length();
                        }

                        if (i < parts.length - 1) {
                            InsertSuggestion newlineSuggestion = copyInsertSuggestion(currentInsertGroup);

                            List<SuggestionSlice> newlineRefs = addSuggestionSlice(
                                    new ArrayList<>(),
                                    runPos,
                                    componentLocalInsertCursor,
                                    1,
                                    opId,
                                    compIdx
                            );

                            ReviewRun newlineRun = ReviewRun.builder()
                                    .text("\n")
                                    .baseAttributes(new LinkedHashMap<>())
                                    .suggestionAttributes(new LinkedHashMap<>())
                                    .references(newlineRefs)
                                    .logicalStart(runPos)
                                    .insertSuggestion(newlineSuggestion)
                                    .build();

                            runs.add(spliceAt++, newlineRun);

                            componentLocalInsertCursor++;
                            runPos++;
                        }
                    }

                    /*
                     * SHIFT LOGICAL STARTS OF FOLLOWING RUNS
                     */
                    for (int i = spliceAt; i < runs.size(); i++) {
                        runs.get(i).setLogicalStart(
                                runs.get(i).getLogicalStart() + shiftLen
                        );
                    }

                    localLogPos += insertText.length();
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

                        if ("\n".equals(run.getText()) && run.getInsertSuggestion() == null) {
                            DeleteSuggestion newlineDelete = copyDeleteSuggestion(currentDeleteGroup);

                            run.setReferences(addSuggestionSlice(
                                    new ArrayList<>(),
                                    run.getLogicalStart(),
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

                            int runStart = target.getLogicalStart();
                            int runEnd = runStart + target.getText().length();

                            List<SuggestionSlice> targetSlices =
                                    target.getReferences().stream()
                                            .filter(slice -> slice.getRef() != null)
                                            .toList();

                            int deleteStartPos = target.getLogicalStart();
                            int deleteLen = target.getText().length();
                            int deleteEndPos = deleteStartPos + deleteLen;

                            runs.remove(cursor);

                            for (int i = cursor; i < runs.size(); i++) {
                                runs.get(i).setLogicalStart(runs.get(i).getLogicalStart() - len);
                            }

                            for (SuggestionSlice slice : targetSlices) {
                                if (slice.getRef() == null) continue;

                                int sliceStart = slice.getReviewStart();
                                int sliceEnd = sliceStart + slice.getLength();

                                int overlapStart = Math.max(runStart, sliceStart);
                                int overlapEnd = Math.min(runEnd, sliceEnd);

                                if (overlapStart >= overlapEnd) continue;

                                int overlapLen = overlapEnd - overlapStart;

                                accumulator.recordInsertCancellation(
                                        slice.getRef().opId(),
                                        slice.getRef().componentIndex(),
                                        slice.getComponentStart() + (overlapStart - sliceStart),
                                        overlapLen
                                );

                                accumulator.recordDeleteCancellation(
                                        opId,
                                        compIdx,
                                        overlapLen
                                );
                            }

                            Iterator<FormatSuggestionItem> fmtIt = formatSuggestions.iterator();

                            while (fmtIt.hasNext()) {
                                FormatSuggestionItem fmt = fmtIt.next();

                                List<SuggestionSlice> fmtRefs = fmt.getReferences();
                                if (fmtRefs == null || fmtRefs.isEmpty()) continue;

                                boolean touched = false;

                                for (SuggestionSlice slice : fmtRefs) {
                                    // slice.getReviewStart() is the logical document position where
                                    // this slice begins — same coordinate space as deleteStartPos/deleteEndPos.
                                    // slice.getComponentStart() is the offset within the format retain
                                    // component — a different space; never compare with doc positions.
                                    int sliceDocStart = slice.getReviewStart();
                                    int sliceDocEnd   = sliceDocStart + slice.getLength();

                                    int overlapStart = Math.max(deleteStartPos, sliceDocStart);
                                    int overlapEnd   = Math.min(deleteEndPos,   sliceDocEnd);

                                    if (overlapStart < overlapEnd) {
                                        int offsetWithinSlice = overlapStart - sliceDocStart;
                                        accumulator.recordFormatCancellation(
                                                slice.getRef().opId(),
                                                slice.getRef().componentIndex(),
                                                fmt.getAttributeKey(),
                                                slice.getComponentStart() + offsetWithinSlice,
                                                overlapEnd - overlapStart
                                        );

                                        touched = true;
                                    }
                                }

                                if (touched) {
                                    removeRangeFromFormatSuggestion(fmt, deleteStartPos, deleteLen);

                                    if (fmt.getSpans().isEmpty()) {
                                        fmtIt.remove();
                                    }
                                }
                            }

                            remaining -= len;
                            localLogPos += len;
                        } else {
                            DeleteSuggestion runDelete = copyDeleteSuggestion(currentDeleteGroup);

                            target.setReferences(addSuggestionSlice(
                                    new ArrayList<>(),
                                    target.getLogicalStart(),
                                    deleteComponentLocalStart,
                                    len,
                                    opId,
                                    compIdx
                            ));

                            target.setDeleteSuggestion(runDelete);

                            int deleteStartPos = target.getLogicalStart();
                            int deleteEndPos = deleteStartPos + len;

                            for (FormatSuggestionItem fmt : formatSuggestions) {
                                if (fmt.getSpans() == null || fmt.getSpans().isEmpty()) continue;

                                boolean overlapsCommittedFormattedText = false;

                                for (FormatSuggestionSpan span : fmt.getSpans()) {
                                    int spanStart = span.getStart();
                                    int spanEnd = spanStart + span.getLength();

                                    boolean overlap =
                                            deleteStartPos < spanEnd &&
                                                    deleteEndPos > spanStart;

                                    if (overlap) {
                                        overlapsCommittedFormattedText = true;
                                        break;
                                    }
                                }

                                if (overlapsCommittedFormattedText) {
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

        for (FormatSuggestionItem fmt : formatSuggestions) {
            if (fmt.getPreviewText() != null && !fmt.getPreviewText().isEmpty()) continue;

            StringBuilder texts = new StringBuilder();
            List<FormatSuggestionSpan> orderedSpans = fmt.getSpans().stream()
                    .sorted(Comparator.comparingInt(FormatSuggestionSpan::getStart))
                    .toList();
            Integer prevSpanEnd = null;

            for (FormatSuggestionSpan span : orderedSpans) {
                int spanStart = span.getStart();
                int spanEnd = span.getStart() + span.getLength();

                if (prevSpanEnd != null && spanStart > prevSpanEnd) {
                    Integer finalPrevSpanEnd = prevSpanEnd;
                    boolean sawNewlineGap = runs.stream()
                            .filter(r -> r.getDeleteSuggestion() == null)
                            .anyMatch(r -> {
                                int rs = r.getLogicalStart(), re = rs + r.getText().length();
                                return re > finalPrevSpanEnd && rs < spanStart && "\n".equals(r.getText());
                            });
                    texts.append(sawNewlineGap ? " ↵ " : " ... ");
                }

                for (ReviewRun run : runs) {
                    if (run.getDeleteSuggestion() != null) continue;
                    int rs = run.getLogicalStart(), re = rs + run.getText().length();
                    if (re > spanStart && rs < spanEnd)
                        texts.append("\n".equals(run.getText()) ? " ↵ " : run.getText());
                }

                prevSpanEnd = spanEnd;
            }

            String preview = texts.toString();
            if (preview.length() > 60) preview = preview.substring(0, 60);
            fmt.setPreviewText(preview);
        }

        if (!accumulator.isEmpty()) {
            log.warn("[ATTRIBUTION] Flushing cancellation accumulator for noteId={}", noteId);

            NoteDto freshNote = redisService.getNote(noteId);
            NoteVersionDto noteVersion = redisService.getNoteVersion(noteId);

            boolean changed = accumulator.flushAndReturnChanged(freshNote.revisionLog());

            if (changed) {
                redisService.updateNote(freshNote, noteVersion);
                notePersistenceService.saveRedisNoteToDatabase(actorEmail, noteId);
            }
        }

        Delta visualDelta = buildVisualDelta(runs);

        for (TextOperation textOp : changeTextOps) {
            baseDelta = baseDelta.compose(new Delta(textOp.getDelta().ops));
        }
        return new ReviewProjection(baseDelta, visualDelta, formatSuggestions);
    }

    private static FormatKeyChangeType getFormatKeyChangeType(FormatSuggestionItem fmt, Object attrValue, Object baseValue) {
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
        List<ReviewRun> collapsed = new ArrayList<>();

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

                last.setReferences(appendSuggestionSlices(
                        last.getReferences(),
                        run.getReferences()
                ));
            } else {
                collapsed.add(ReviewRun.builder()
                        .text(run.getText())
                        .baseAttributes(new LinkedHashMap<>(run.getBaseAttributes() != null ? run.getBaseAttributes() : Collections.emptyMap()))
                        .suggestionAttributes(new LinkedHashMap<>(run.getSuggestionAttributes() != null ? run.getSuggestionAttributes() : Collections.emptyMap()))
                        .references(cloneSuggestionSlices(run.getReferences()))
                        .logicalStart(run.getLogicalStart())
                        .insertSuggestion(run.getInsertSuggestion() != null ? copyInsertSuggestion(run.getInsertSuggestion()) : null)
                        .deleteSuggestion(run.getDeleteSuggestion() != null ? copyDeleteSuggestion(run.getDeleteSuggestion()) : null)
                        .build());
            }
        }

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
                insertPayload.put("suggestionAttributes", !suggestionAttrs.isEmpty() ? suggestionAttrs : null);
                attrs.put("suggestion-insert", insertPayload);
            }

            if (run.getDeleteSuggestion() != null) {
                Map<String, Object> deletePayload = new LinkedHashMap<>();
                deletePayload.put("groupId", run.getDeleteSuggestion().getGroupId());
                deletePayload.put("actorEmail", run.getDeleteSuggestion().getActorEmail());
                deletePayload.put("createdAt", run.getDeleteSuggestion().getCreatedAt());
                deletePayload.put("references", run.getReferences());
                deletePayload.put("baseAttributes", !baseAttrs.isEmpty() ? baseAttrs : null);
                deletePayload.put("suggestionAttributes", !suggestionAttrs.isEmpty() ? suggestionAttrs : null);

                if (isDeletedNewline) {
                    attrs.put("suggestion-delete-newline", deletePayload);
                } else {
                    attrs.put("suggestion-delete", deletePayload);
                }
            }

            if (isDeletedPending) {
                String textToInsert = isDeletedNewline ? "↵" : run.getText();
                delta.insert(textToInsert, attrs.isEmpty() ? null : attrs);
            } else {
                delta.retain(run.getText().length(), attrs.isEmpty() ? null : attrs);
            }
        }

        return delta;
    }
}