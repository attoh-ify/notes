package com.example.notes.dto.attribution;

public record FormatKeyDecision(
        String key,
        Object incomingValue,
        FormatKeyChangeType type
) {}
