package com.example.notes.dto.attribution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BlockFormatChangeItem {
    private String groupId;
    private String actorEmail;
    private String createdAt;
    private String attributeKey;
    private Object attributeValue;
    private BlockFormatBehavior behavior;
    private BlockFormatConflictGroup conflictGroup;
    @Builder.Default
    private List<Reference> references = new ArrayList<>();
    @Builder.Default
    private String previewText = "";
    @Builder.Default
    private List<String> dependsOnInsertGroupIds = new ArrayList<>();
    @Builder.Default
    private List<String> dependsOnDeleteGroupIds = new ArrayList<>();
}