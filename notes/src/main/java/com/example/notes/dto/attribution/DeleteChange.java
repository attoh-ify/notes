package com.example.notes.dto.attribution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeleteChange {
    private String groupId;
    private String actorEmail;
    private String createdAt;
    @Builder.Default
    private DeleteChangeType type = DeleteChangeType.TEXT;

    public enum DeleteChangeType {
        TEXT,
        SINGLE_LINE,
        MULTI_LINE
    }
}