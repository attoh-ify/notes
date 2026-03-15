package com.example.notes.services.impl;

import com.example.notes.dto.note.NoteDto;
import com.example.notes.dto.noteVersion.NoteVersionDto;
import com.example.notes.dto.ot.TextOperation;
import com.example.notes.entities.note.Note;
import com.example.notes.entities.noteVersion.NoteVersion;
import com.example.notes.services.RedisService;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RedisServiceImpl implements RedisService {
    private static final Logger log =
            LoggerFactory.getLogger(RedisServiceImpl.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final NotePolicyService notePolicyService;

    private static final String[] COLLABORATOR_COLORS = {
            "#1F3A93",
            "#D32F2F",
            "#00796B",
            "#F57C00",
            "#512DA8",
            "#C2185B",
            "#303F9F",
            "#388E3C",
            "#FBC02D",
            "#455A64",
            "#E64A19",
            "#5D4037",
            "#1976D2",
            "#7B1FA2",
            "#0097A7",
            "#AFB42B",
            "#6A1B9A",
            "#C62828",
            "#00838F",
            "#AD1457",
            "#283593",
            "#2E7D32",
            "#EF6C00",
            "#4A148C",
            "#00695C",
            "#8E24AA",
            "#B71C1C",
            "#1565C0",
            "#1B5E20",
            "#FF8F00",
            "#880E4F",
            "#0D47A1",
            "#004D40",
            "#6D4C41",
            "#37474F",
            "#E65100",
            "#311B92",
            "#827717",
            "#01579B",
            "#4E342E",
            "#1A237E",
            "#33691E",
            "#BF360C",
            "#3E2723",
            "#263238",
            "#F9A825",
            "#8D6E63",
            "#9E9D24",
            "#0288D1",
            "#D84315"

    };

    public RedisServiceImpl(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, NotePolicyService notePolicyService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.notePolicyService = notePolicyService;
    }

    @Override
    public void initializeNote(String actorEmail, UUID noteId) {
        String noteKey = getNoteKey(noteId);

        if (redisTemplate.hasKey(noteKey)) return;

        Note note = notePolicyService.validateEditor(actorEmail, noteId);
        NoteVersion noteVersion = notePolicyService.findNoteVersionByNoteId(noteId);

        NoteDto redisNote = new NoteDto(
                note.getId(),
                note.getUser().getEmail(),
                note.getTitle(),
                note.getRevisionLog(),
                note.getVisibility(),
                null,
                note.getCurrentNoteVersionNumber(),
                note.getCreatedAt(),
                note.getUpdatedAt()
        );

        NoteVersionDto redisNoteVersion = new NoteVersionDto(
                noteVersion.getId(),
                noteVersion.getMasterDelta(),
                noteVersion.getRevision(),
                noteVersion.getComment(),
                noteVersion.getVersionNumber(),
                noteVersion.getCreatedAt()
        );

        String noteVersionKey = getNoteVersionKey(note.getId());
        String initialRevisionKey = getInitialRevisionKey(noteId);

        try {
            String jsonNote = objectMapper.writeValueAsString(redisNote);
            String jsonNoteVersion = objectMapper.writeValueAsString(redisNoteVersion);

            redisTemplate.opsForValue().set(initialRevisionKey, String.valueOf(noteVersion.getRevision()));
            redisTemplate.opsForValue().set(noteKey, jsonNote);
            redisTemplate.opsForValue().set(noteVersionKey, jsonNoteVersion);
        } catch (JsonProcessingException e) {
            log.error("Failed to initialize note in Redis: {}", noteId, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void updateNote(NoteDto note, NoteVersionDto noteVersion) {
        String noteKey = getNoteKey(note.id());
        String noteVersionKey = getNoteVersionKey(note.id());

        if (redisTemplate.opsForValue().get(noteKey) == null) return;

        try {
            String jsonNote = objectMapper.writeValueAsString(note);
            String jsonNoteVersion = objectMapper.writeValueAsString(noteVersion);

            redisTemplate.opsForValue().set(noteKey, jsonNote);
            redisTemplate.opsForValue().set(noteVersionKey, jsonNoteVersion);
        } catch (Exception e) {
            log.error("Failed to update note: {}", note.id(), e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public NoteDto getNote(UUID noteId) {
        String key = getNoteKey(noteId);
        String jsonNote = redisTemplate.opsForValue().get(key);

        if (jsonNote == null) return null;

        try {
            return objectMapper.readValue(jsonNote, NoteDto.class);
        } catch (Exception e) {
            throw new RuntimeException("Error parsing Note", e);
        }
    }

    @Override
    public void deleteNote(UUID noteId) {
        String noteKey = getNoteKey(noteId);
        String noteVersionKey = getNoteVersionKey(noteId);
        String noteCollaboratorKey = getNoteCollaboratorsKey(noteId);
        String noteInitialRevisionKey = getInitialRevisionKey(noteId);

        redisTemplate.delete(List.of(noteKey, noteVersionKey, noteCollaboratorKey, noteInitialRevisionKey));
    }

    @Override
    public NoteVersionDto getNoteVersion(UUID noteId) {
        String key = getNoteVersionKey(noteId);
        String jsonNoteVersion = redisTemplate.opsForValue().get(key);

        if (jsonNoteVersion == null) return null;

        try {
            return objectMapper.readValue(jsonNoteVersion, NoteVersionDto.class);
        } catch (Exception e) {
            throw new RuntimeException("Error parsing NoteVersion", e);
        }
    }

    @Override
    public void addCollaboratorToNote(UUID noteId, String actorEmail) {
        String key = getNoteCollaboratorsKey(noteId);

        Object existingColor = redisTemplate.opsForHash().get(key, actorEmail);
        if (existingColor != null) return;

        String assignColor = COLLABORATOR_COLORS[Math.abs(actorEmail.hashCode() % COLLABORATOR_COLORS.length)];

        redisTemplate.opsForHash().put(key, actorEmail, assignColor);
    }

    @Override
    public void removeCollaboratorFromNote(UUID noteId, String actorEmail) {
        String key = getNoteCollaboratorsKey(noteId);
        redisTemplate.opsForHash().delete(key, actorEmail);
    }

    @Override
    public Map<Object, Object> getCollaborators(UUID noteId) {
        String key = getNoteCollaboratorsKey(noteId);
        return redisTemplate.opsForHash().entries(key);
    }

    @Override
    public void setReviewInProgress(UUID noteId, String ownerEmail, String value) {
        if ("true".equalsIgnoreCase(value)) {
            redisTemplate.opsForHash().put(getReviewInProgressKey(noteId), ownerEmail, "true");
        } else {
            redisTemplate.opsForHash().delete(getReviewInProgressKey(noteId), ownerEmail);
        }
    }

    @Override
    public boolean isReviewInProgress(UUID noteId, String ownerEmail) {
        Object val = redisTemplate.opsForHash().get(getReviewInProgressKey(noteId), ownerEmail);
        return "true".equals(val);
    }

    @Override
    public int getInitialRevision(UUID noteId) {
        String val = redisTemplate.opsForValue().get(getInitialRevisionKey(noteId));
        return val != null ? Integer.parseInt(val) : 0;
    }

    private String getNoteKey(UUID noteId) {
        return "note:" + noteId;
    }

    private String getNoteVersionKey(UUID noteId) {
        return "note-version:" + noteId;
    }

    private String getNoteCollaboratorsKey(UUID noteId) {
        return "note-collaborators:" + noteId;
    }

    private String getInitialRevisionKey(UUID noteId) {
        return "note-initial-revision:" + noteId;
    }

    private String getReviewInProgressKey(UUID noteId) {
        return "note-review-in-progress:" + noteId;
    }
}
