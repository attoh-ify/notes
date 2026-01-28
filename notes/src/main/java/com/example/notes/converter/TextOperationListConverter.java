package com.example.notes.converter;

import com.example.notes.dto.ot.TextOperation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;

@Converter
public class TextOperationListConverter implements AttributeConverter<List<TextOperation>, String> {
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<TextOperation> attribute) {
        try {
            return mapper.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize revision log", e);
        }
    }

    @Override
    public List<TextOperation> convertToEntityAttribute(String dbData) {
        try {
            return mapper.readValue(
                    dbData,
                    new TypeReference<List<TextOperation>>() {}
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize revision log", e);
        }
    }
}
