package com.example.notes.dto.attribution;

import com.example.notes.dto.ot.Delta;
import java.util.List;

public record ReviewProjection(
        Delta baseDelta,
        Delta visualDelta,
        List<FormatSuggestionItem> formatSuggestions,
        List<BlockFormatSuggestionItem> blockFormatSuggestions
) {}