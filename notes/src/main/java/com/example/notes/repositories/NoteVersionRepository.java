package com.example.notes.repositories;

import com.example.notes.entities.noteVersion.NoteVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NoteVersionRepository extends JpaRepository<NoteVersion, UUID> {
    @Query("SELECT MAX(nv.versionNumber) FROM NoteVersion nv WHERE nv.note.id = :noteId")
    Integer findMaxVersionByNoteId(@Param("noteId") UUID noteId);
    Optional<NoteVersion> findByNote_IdAndId(UUID noteId, UUID versionId);
    List<NoteVersion> findByNoteIdOrderByVersionNumberAsc(UUID noteId);
    Optional<NoteVersion> findTopByNote_IdOrderByVersionNumberDesc(UUID noteId);
    Optional<NoteVersion> findByNote_IdAndVersionNumber(UUID noteId, int versionNumber);
}
