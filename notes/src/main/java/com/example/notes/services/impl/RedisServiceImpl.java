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

import java.time.Duration;
import java.util.*;

@Service
public class RedisServiceImpl implements RedisService {
    private int initialRevision;
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
        String noteKey = getNoteKey(noteId);

        if (redisTemplate.hasKey(noteKey)) return;

        Note note = notePolicyService.validateEditor(actorEmail, noteId);
        note.setNoteVersions(null);
        note.setUser(null);
        note.setNoteAccesses(null);
        NoteVersion noteVersion = notePolicyService.findNoteVersionByNoteId(noteId);

        String noteVersionKey = getNoteVersionKey(note.getId());

        String key = getInitialRevisionKey(noteId);

        try {
            String jsonNote = objectMapper.writeValueAsString(note);
            String jsonNoteVersion = objectMapper.writeValueAsString(noteVersion);

            redisTemplate.opsForValue().set(key, String.valueOf(noteVersion.getRevision()));
            redisTemplate.opsForValue().set(noteKey, jsonNote);
            redisTemplate.opsForValue().set(noteVersionKey, jsonNoteVersion);
        } catch (JsonProcessingException e) {
            log.error("Failed to initialize note in Redis: {}", noteId, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void updateNote(Note note, NoteVersion noteVersion) {
        String noteKey = getNoteKey(note.getId());
        String noteVersionKey = getNoteVersionKey(note.getId());

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
        String key = getNoteKey(noteId);
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
        String noteKey = getNoteKey(noteId);
        String noteVersionKey = getNoteVersionKey(noteId);
        String noteCollaboratorKey = getNoteCollaboratorsKey(noteId);

        redisTemplate.delete(List.of(noteKey, noteVersionKey, noteCollaboratorKey));
    }

    @Override
    public NoteVersion getNoteVersion(UUID noteId) {
        String key = getNoteVersionKey(noteId);
        String jsonNoteVersion = redisTemplate.opsForValue().get(key);

        if (jsonNoteVersion == null) return null;

        try {
            return objectMapper.readValue(jsonNoteVersion, NoteVersion.class);
        } catch (Exception e) {
            throw new RuntimeException("Error parsing NoteVersion", e);
        }
    }

    @Override
    public int getInitialRevision(UUID noteId) {
        String key = getInitialRevisionKey(noteId);
        String val = redisTemplate.opsForValue().get(key);
        return val != null ? Integer.parseInt(val) : 0;
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
    public Boolean isCollaborator(String actorEmail) {
        return null;
    }

    @Override
    public boolean acquireLock(UUID noteId) {
        String lockKey = getNoteLockKey(noteId);
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "locked", Duration.ofSeconds(1));
        return acquired != null && acquired;
    }

    @Override
    public void releaseLock(UUID noteId) {
        String lockKey = getNoteLockKey(noteId);
        redisTemplate.delete(lockKey);
    }

    private String getInitialRevisionKey(UUID noteId) {
        return "note-initial-revision:" + noteId;
    }

    private String getNoteLockKey(UUID noteId) {
        return "lock:note:" + noteId;
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
}
