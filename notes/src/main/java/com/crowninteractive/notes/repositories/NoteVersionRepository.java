package com.crowninteractive.notes.repositories;

import com.crowninteractive.notes.entities.noteVersion.NoteVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NoteVersionRepository extends JpaRepository<NoteVersion, Long> {
    Optional<NoteVersion> findByNoteVersionId(String noteVersionId);
    @Query("SELECT MAX(nv.versionNumber) FROM NoteVersion nv WHERE nv.note.noteId = :noteId")
    Integer findMaxVersionByNote_NoteId(@Param("noteId") String noteId);
    Optional<NoteVersion> findByNote_NoteIdAndNoteVersionId(String noteId, String noteVersionId);
    List<NoteVersion> findByNote_NoteIdOrderByVersionNumberAsc(String noteId);
    Optional<NoteVersion> findByNote_NoteIdAndVersionNumber(String noteId, int versionNumber);
}
