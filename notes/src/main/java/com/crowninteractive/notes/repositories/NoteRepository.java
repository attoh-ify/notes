package com.crowninteractive.notes.repositories;

import com.crowninteractive.notes.entities.note.Note;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NoteRepository extends JpaRepository<Note, Long> {
    Optional<Note> findByNoteId(String noteId);
    @Query("""
            SELECT n FROM Note n
            LEFT JOIN NoteAccess na ON na.note = n
            WHERE n.user.email = :actorEmail
                OR na.email = :actorEmail
            """)
    List<Note> findByActorEmail(String actorEmail);
}
