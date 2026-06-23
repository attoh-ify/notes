package com.crowninteractive.notes.converter;

import com.crowninteractive.notes.dto.ot.Delta;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.persistence.AttributeConverter;
import javax.persistence.Converter;
import java.io.IOException;

@Converter(autoApply = false)
public class DeltaJsonConverter implements AttributeConverter<Delta, String> {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(Delta delta) {
        if (delta == null) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(delta);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not convert Delta to JSON", e);
        }
    }

    @Override
    public Delta convertToEntityAttribute(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }

        try {
            return objectMapper.readValue(json, Delta.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not convert JSON to Delta", e);
        }
    }
}