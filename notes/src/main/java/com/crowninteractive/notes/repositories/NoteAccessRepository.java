package com.crowninteractive.notes.repositories;

import com.crowninteractive.notes.entities.noteAccess.NoteAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NoteAccessRepository extends JpaRepository<NoteAccess, Long> {
    Optional<NoteAccess> findByNoteAccessId(String noteAccessId);
    void deleteByNoteAccessId(String noteAccessId);
    List<NoteAccess> findByNote_NoteId(String noteId);
}
