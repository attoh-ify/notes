package com.example.notes.services.impl;

import com.example.notes.dto.message_payload.CollaboratorsPayload;
import com.example.notes.dto.note.NoteDto;
import com.example.notes.dto.noteAccess.NoteAccessPayload;
import com.example.notes.dto.noteAccess.NoteAccessDto;
import com.example.notes.entities.note.Note;
import com.example.notes.entities.noteAccess.NoteAccess;
import com.example.notes.entities.noteAccess.NoteAccessRole;
import com.example.notes.entities.user.User;
import com.example.notes.exceptions.BadRequestException;
import com.example.notes.mappers.NoteAccessMapper;
import com.example.notes.notifier.CollaboratorCountNotifier;
import com.example.notes.repositories.NoteAccessRepository;
import com.example.notes.services.EmailService;
import com.example.notes.services.NoteAccessService;
import com.example.notes.services.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class NoteAccessServiceImpl implements NoteAccessService {
    private final NoteAccessRepository noteAccessRepository;
    private final NoteAccessMapper noteAccessMapper;
    private final NotePolicyService notePolicyService;
    private final UserPolicyService userPolicyService;
    private final EmailService emailService;
    private final RedisService redisService;

    @Autowired
    public CollaboratorCountNotifier collaboratorCountNotifier;

    private static final Logger log =
            LoggerFactory.getLogger(NoteAccessServiceImpl.class);

    public NoteAccessServiceImpl(NoteAccessRepository noteAccessRepository, NoteAccessMapper noteAccessMapper, NotePolicyService notePolicyService, UserPolicyService userPolicyService, EmailService emailService, RedisService redisService) {
        this.noteAccessRepository = noteAccessRepository;
        this.noteAccessMapper = noteAccessMapper;
        this.userPolicyService = userPolicyService;
        this.notePolicyService = notePolicyService;
        this.emailService = emailService;
        this.redisService = redisService;
    }

    @Transactional
    @Override
    public NoteAccessDto addAccess(String userEmail, UUID noteId, NoteAccessPayload noteAccess) {
        Note note = notePolicyService.validateSuper(userEmail, noteId);

        if (noteAccess.email().equals(userEmail)) {
            log.warn("Owner already has access to this note");
            throw new BadRequestException("Owner already has access to this note");
        }

        if (noteAccess.role().equals(NoteAccessRole.OWNER)) {
            log.warn("User can not be granted owner role");
            throw new BadRequestException("User can not be granted owner role");
        }

        User newAccessUser = userPolicyService.userExists(noteAccess.email());
        emailService.sendAccessGrantedEmail(noteAccess.email(), note.getTitle(), noteAccess.role());

        try {
            return noteAccessMapper.toDto(
                    noteAccessRepository.save(
                            new NoteAccess(
                                    null,
                                    note,
                                    newAccessUser.getEmail(),
                                    noteAccess.role()
                            )
                    )
            );
        } catch (DataIntegrityViolationException e) {
            log.warn("Note access already exists for the email={}", noteAccess.email());
            throw new BadRequestException("Note access already exists for the provided email");
        }
    }

    @Transactional
    @Override
    public NoteAccessDto updateAccess(String userEmail, UUID noteId, UUID noteAccessId, NoteAccessPayload noteAccess) {
        Note note = notePolicyService.validateSuper(userEmail, noteId);
        NoteAccess updateNoteAccess = noteAccessRepository.findById(noteAccessId)
                .orElseThrow(() -> {
                    log.warn("Note access not found id={}", noteAccessId);
                    return new BadRequestException(
                            "Note access with this id is not registered."
                    );
                });
        updateNoteAccess.setRole(noteAccess.role());

        if (noteAccess.role() != NoteAccessRole.EDITOR
                && noteAccess.role() != NoteAccessRole.SUPER) {
            redisService.removeCollaboratorFromNote(noteId, updateNoteAccess.getEmail());
            Map<Object, Object> collaborators = redisService.getCollaborators(noteId);

            collaboratorCountNotifier.notifyCount(
                    noteId,
                    new CollaboratorsPayload(collaborators)
            );
        }
        emailService.sendAccessUpdatedEmail(noteAccess.email(), note.getTitle(), noteAccess.role());
        return noteAccessMapper.toDto(noteAccessRepository.save(updateNoteAccess));
    }

    @Transactional
    @Override
    public void deleteAccess(String userEmail, UUID noteId, UUID noteAccessId) {
        Note note = notePolicyService.validateSuper(userEmail, noteId);
        NoteAccess noteAccess = noteAccessRepository.findById(noteAccessId)
                .orElseThrow(() -> {
                    log.warn("Note access not found id={}", noteAccessId);
                    return new BadRequestException(
                            "Note access with this id is not registered."
                    );
                });
        noteAccessRepository.deleteById(noteAccessId);

        redisService.removeCollaboratorFromNote(noteId, noteAccess.getEmail());
        Map<Object, Object> collaborators = redisService.getCollaborators(noteId);

        collaboratorCountNotifier.notifyCount(
                noteId,
                new CollaboratorsPayload(collaborators)
        );
        emailService.sendAccessDeletedEmail(noteAccess.getEmail(), note.getTitle());
    }

    @Transactional(readOnly = true)
    @Override
    public List<NoteAccessDto> getAllAccess(String userEmail, UUID noteId) {
        notePolicyService.validateEditor(userEmail, noteId);
        return noteAccessRepository.findByNoteId(noteId)
                .stream()
                .map(noteAccessMapper::toDto)
                .toList();
    }
}
