package com.example.notes.services.impl;

import com.example.notes.entities.note.Note;
import com.example.notes.entities.noteVersion.NoteVersion;
import com.example.notes.services.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class RedisServiceImpl implements RedisService {
    private static final Logger log =
            LoggerFactory.getLogger(RedisServiceImpl.class);

    private final StringRedisTemplate redisTemplate;
    private final NotePolicyService notePolicyService;

    public RedisServiceImpl(StringRedisTemplate redisTemplate, NotePolicyService notePolicyService) {
        this.redisTemplate = redisTemplate;
        this.notePolicyService = notePolicyService;
    }

    @Override
    public void initializeNote(String actorEmail, UUID noteId) {

    }

    @Override
    public void updateNote(String actorEmail, UUID noteId, Note note, NoteVersion noteVersion) {

    }

    @Override
    public Note getNote(String actorEmail, UUID noteId) {
        return null;
    }

    @Override
    public void deleteNote(String actorEmail, UUID noteId) {

    }

    @Override
    public NoteVersion getNoteVersion(String actorEmail, UUID noteId) {
        return null;
    }

    @Override
    public void addCollaboratorToNote(UUID noteId, String actorEmail) {

    }

    @Override
    public void removeCollaboratorFromNote(UUID noteId, String actorEmail) {

    }

    @Override
    public List<String> getCollaborators(String actorEmail, UUID noteId) {
        return List.of();
    }
}
