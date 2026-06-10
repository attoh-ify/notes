package com.example.notes.dto.attribution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChangeSegment {
    private String id;
    private String text;
    private Object embed;

    @Builder.Default
    private Map<String, Object> baseAttributes = new HashMap<>();

    @Builder.Default
    private Map<String, Object> changeAttributes = new HashMap<>();

    @Builder.Default
    private List<Reference> references = new ArrayList<>();

    private int logicalStart;
    private InsertChange insertChange;
    private DeleteChange deleteChange;

    public boolean isEmbed() {
        return embed != null;
    }

    public boolean isText() {
        return text != null;
    }

    public boolean isNewline() {
        return isText() && "\n".equals(text);
    }

    public int length() {
        if (isEmbed()) return 1;
        if (isText()) return text.length();
        return 0;
    }
}