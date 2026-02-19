package com.example.notes.entities.noteVersion;

import com.example.notes.dto.ot.Delta;
import com.example.notes.entities.note.Note;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Setter
@Getter
@Entity
@Table(
        name = "note_versions",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"note_id", "version_number"}
        )
)
public class NoteVersion {
    @Id
    @Column(name = "id", updatable = false, nullable = false)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "note_id", nullable = false)
    private Note note;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "master_delta", nullable = false)
    private Delta masterDelta;

    @Column(name = "revision", nullable = false)
    private int revision;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public NoteVersion() {}

    public NoteVersion(
            UUID id,
            Note note,
            Delta masterDelta,
            int revision,
            UUID createdBy,
            Integer versionNumber
    ) {
        this.id = id;
        this.note = note;
        this.masterDelta = masterDelta;
        this.revision = revision;
        this.createdBy = createdBy;
        this.versionNumber = versionNumber;
    }

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return "NoteVersion{" +
                "id=" + id +
                ", note=" + note +
                ", masterDelta=" + masterDelta +
                ", revision=" + revision +
                ", createdBy=" + createdBy +
                ", versionNumber=" + versionNumber +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}