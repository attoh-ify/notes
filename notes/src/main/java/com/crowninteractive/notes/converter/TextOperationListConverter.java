package com.crowninteractive.notes.converter;

import com.crowninteractive.notes.dto.ot.TextOperation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import javax.persistence.AttributeConverter;
import javax.persistence.Converter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Converter
public class TextOperationListConverter implements AttributeConverter<List<TextOperation>, String> {

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Override
    public String convertToDatabaseColumn(List<TextOperation> textOperations) {
        if (textOperations == null || textOperations.isEmpty()) {
            return "[]";
        }

        try {
            return objectMapper.writeValueAsString(textOperations);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not convert TextOperations to JSON", e);
        }
    }

    @Override
    public List<TextOperation> convertToEntityAttribute(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new ArrayList<TextOperation>();
        }

        try {
            return objectMapper.readValue(json, new TypeReference<List<TextOperation>>() {});
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not convert JSON to TextOperations", e);
        }
    }
}