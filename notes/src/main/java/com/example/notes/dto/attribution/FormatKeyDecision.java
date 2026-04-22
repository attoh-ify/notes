package com.example.notes.dto.attribution;

public record FormatKeyDecision(
        String key,
        Object baseValue,
        Object suggestedValue,
        Object incomingValue,
        FormatKeyChangeType type
) {}
