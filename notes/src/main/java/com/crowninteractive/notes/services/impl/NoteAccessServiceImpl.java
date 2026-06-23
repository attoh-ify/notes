package com.crowninteractive.notes.services.impl;

import com.crowninteractive.notes.dto.message_payload.CollaboratorsPayload;
import com.crowninteractive.notes.dto.noteAccess.NoteAccessPayload;
import com.crowninteractive.notes.dto.noteAccess.NoteAccessDto;
import com.crowninteractive.notes.entities.note.Note;
import com.crowninteractive.notes.entities.noteAccess.NoteAccess;
import com.crowninteractive.notes.entities.noteAccess.NoteAccessRole;
import com.crowninteractive.notes.entities.user.User;
import com.crowninteractive.notes.exceptions.BadRequestException;
import com.crowninteractive.notes.mappers.NoteAccessMapper;
import com.crowninteractive.notes.notifier.CollaboratorCountNotifier;
import com.crowninteractive.notes.repositories.NoteAccessRepository;
import com.crowninteractive.notes.services.EmailService;
import com.crowninteractive.notes.services.NoteAccessService;
import com.crowninteractive.notes.services.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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
    public NoteAccessDto addAccess(String userEmail, String noteId, NoteAccessPayload noteAccess) {
        Note note = notePolicyService.validateSuper(userEmail, noteId);

        if (noteAccess.getEmail().equals(userEmail)) {
            log.warn("Owner already has access to this note");
            throw new BadRequestException("Owner already has access to this note");
        }

        if (noteAccess.getRole().equals(NoteAccessRole.OWNER)) {
            log.warn("User can not be granted owner role");
            throw new BadRequestException("User can not be granted owner role");
        }

        User newAccessUser = userPolicyService.userExists(noteAccess.getEmail());
        emailService.sendAccessGrantedEmail(noteAccess.getEmail(), note.getTitle(), noteAccess.getRole());

        try {
            return noteAccessMapper.toDto(
                    noteAccessRepository.save(
                            new NoteAccess(
                                    null,
                                    UUID.randomUUID().toString(),
                                    note,
                                    newAccessUser.getEmail(),
                                    noteAccess.getRole()
                            )
                    )
            );
        } catch (DataIntegrityViolationException e) {
            log.warn("Note access already exists for the email={}", noteAccess.getEmail());
            throw new BadRequestException("Note access already exists for the provided email");
        }
    }

    @Transactional
    @Override
    public NoteAccessDto updateAccess(String userEmail, String noteId, String noteAccessId, NoteAccessPayload noteAccess) {
        Note note = notePolicyService.validateSuper(userEmail, noteId);
        NoteAccess updateNoteAccess = noteAccessRepository.findByNoteAccessId(noteAccessId)
                .orElseThrow(() -> {
                    log.warn("Note access not found id={}", noteAccessId);
                    return new BadRequestException(
                            "Note access with this id is not registered."
                    );
                });
        updateNoteAccess.setRole(noteAccess.getRole());

        if (noteAccess.getRole() != NoteAccessRole.EDITOR
                && noteAccess.getRole() != NoteAccessRole.SUPER) {
            redisService.removeCollaboratorFromNote(noteId, updateNoteAccess.getEmail());
            Map<Object, Object> collaborators = redisService.getCollaborators(noteId);

            collaboratorCountNotifier.notifyCount(
                    noteId,
                    new CollaboratorsPayload(collaborators)
            );
        }
        emailService.sendAccessUpdatedEmail(noteAccess.getEmail(), note.getTitle(), noteAccess.getRole());
        return noteAccessMapper.toDto(noteAccessRepository.save(updateNoteAccess));
    }

    @Transactional
    @Override
    public void deleteAccess(String userEmail, String noteId, String noteAccessId) {
        Note note = notePolicyService.validateSuper(userEmail, noteId);
        NoteAccess noteAccess = noteAccessRepository.findByNoteAccessId(noteAccessId)
                .orElseThrow(() -> {
                    log.warn("Note access not found id={}", noteAccessId);
                    return new BadRequestException(
                            "Note access with this id is not registered."
                    );
                });
        noteAccessRepository.deleteByNoteAccessId(noteAccessId);

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
    public List<NoteAccessDto> getAllAccess(String userEmail, String noteId) {
        notePolicyService.validateEditor(userEmail, noteId);
        return noteAccessRepository.findByNote_NoteId(noteId)
                .stream()
                .map(noteAccessMapper::toDto)
                .collect(Collectors.toList());
    }
}
