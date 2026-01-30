package com.example.notes.repositories;

import com.example.notes.entities.note.Note;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NoteRepository extends JpaRepository<Note, UUID> {
    @Query("""
            SELECT DISTINCT n FROM Note n
            LEFT JOIN NoteAccess na ON na.note = n
            WHERE n.user.email = :actorEmail
                OR na.email = :actorEmail
            """)
    List<Note> findByActorEmail(String actorEmail);
    @Transactional
    @Query("SELECT n FROM Note n LEFT JOIN FETCH n.noteAccesses WHERE n.id = :noteId")
    Optional<Note> findByIdWithAccesses(@Param("noteId") UUID noteId);

}
