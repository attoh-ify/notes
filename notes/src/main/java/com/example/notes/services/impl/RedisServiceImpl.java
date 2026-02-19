package com.example.notes.services.impl;

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
            "#4285F4", // Google Blue
            "#F4B400", // Google Yellow
            "#0F9D58", // Google Green
            "#9C27B0", // Deep Purple
            "#FF7043", // Deep Orange
            "#00BCD4", // Cyan
            "#795548", // Brown
            "#607D8B", // Blue Grey
            "#E91E63"  // Pink
    };

    public RedisServiceImpl(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, NotePolicyService notePolicyService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.notePolicyService = notePolicyService;
    }

    @Override
    public void initializeNote(String actorEmail, UUID noteId) {
        String noteKey = "note:" + noteId;

        if (redisTemplate.hasKey(noteKey)) return;

        Note note = notePolicyService.validateEditor(actorEmail, noteId);
        note.setNoteVersions(null);
        note.setUser(null);
        note.setNoteAccesses(null);
        NoteVersion noteVersion = notePolicyService.findNoteVersionByNoteId(noteId);

        String noteVersionKey = "note-version:" + note.getId();

        try {
            String jsonNote = objectMapper.writeValueAsString(note);
            String jsonNoteVersion = objectMapper.writeValueAsString(noteVersion);

            redisTemplate.opsForValue().set(noteKey, jsonNote);
            redisTemplate.opsForValue().set(noteVersionKey, jsonNoteVersion);
        } catch (JsonProcessingException e) {
            log.error("Failed to initialize note in Redis: {}", noteId, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void updateNote(Note note, NoteVersion noteVersion) {
        String noteKey = "note:" + note.getId();
        String noteVersionKey = "note-version:" + note.getId();

        if (redisTemplate.opsForValue().get(noteKey) == null) return;

        try {
            String jsonNote = objectMapper.writeValueAsString(note);
            String jsonNoteVersion = objectMapper.writeValueAsString(noteVersion);

            redisTemplate.opsForValue().set(noteKey, jsonNote);
            redisTemplate.opsForValue().set(noteVersionKey, jsonNoteVersion);
        } catch (Exception e) {
            log.error("Failed to update note: {}", note.getId(), e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public Note getNote(UUID noteId) {
        System.out.println(8);
        String key = "note:" + noteId;
        String jsonNote = redisTemplate.opsForValue().get(key);

        if (jsonNote == null) return null;

        try {
            return objectMapper.readValue(jsonNote, Note.class);
        } catch (Exception e) {
            throw new RuntimeException("Error parsing Note", e);
        }
    }

    @Override
    public void deleteNote(UUID noteId) {
        String noteKey = "note:" + noteId;
        String noteVersionKey = "note-version:" + noteId;
        String noteCollaboratorKey = "note-collaborators:" + noteId;

        redisTemplate.delete(List.of(noteKey, noteVersionKey, noteCollaboratorKey));
    }

    @Override
    public NoteVersion getNoteVersion(UUID noteId) {
        String key = "note-version:" + noteId;
        String jsonNoteVersion = redisTemplate.opsForValue().get(key);

        if (jsonNoteVersion == null) return null;

        try {
            return objectMapper.readValue(jsonNoteVersion, NoteVersion.class);
        } catch (Exception e) {
            throw new RuntimeException("Error parsing NoteVersion", e);
        }
    }

    @Override
    public void addCollaboratorToNote(UUID noteId, String actorEmail) {
        String key = "note-collaborators:" + noteId;

        Object existingColor = redisTemplate.opsForHash().get(key, actorEmail);
        if (existingColor != null) return;

        String assignColor = COLLABORATOR_COLORS[Math.abs(actorEmail.hashCode() % COLLABORATOR_COLORS.length)];

        redisTemplate.opsForHash().put(key, actorEmail, assignColor);
    }

    @Override
    public void removeCollaboratorFromNote(UUID noteId, String actorEmail) {
        String key = "note-collaborators:" + noteId;
        redisTemplate.opsForHash().delete(key, actorEmail);
    }

    @Override
    public Map<Object, Object> getCollaborators(UUID noteId) {
        String key = "note-collaborators:" + noteId;
        return redisTemplate.opsForHash().entries(key);
    }

    @Override
    public Boolean isCollaborator(String actorEmail) {
        return null;
    }
}
