package com.example.notes.entities.note;

import com.example.notes.entities.noteAccess.NoteAccess;
import com.example.notes.entities.noteVersion.NoteVersion;
import com.example.notes.entities.user.User;
import com.example.notes.dto.ot.TextOperation;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Setter
@Getter
@Entity
@Table(name = "notes")
public class Note {
    @Id
    @Column(name = "id", updatable = false, nullable = false)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "title", nullable = false)
    private String title;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "revision_log", nullable = false)
    private List<TextOperation> revisionLog = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false)
    private NoteVisibility visibility;

    @OneToMany(mappedBy = "note", cascade = {CascadeType.REMOVE, CascadeType.PERSIST}, fetch = FetchType.LAZY)
    private List<NoteAccess> noteAccesses;

    @Column(name = "current_note_version_number")
    private int currentNoteVersionNumber;

    @OneToMany(mappedBy = "note", cascade = {CascadeType.REMOVE, CascadeType.PERSIST}, fetch = FetchType.LAZY)
    private List<NoteVersion> noteVersions = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Note() {}

    public Note(UUID id, User user, String title, List<TextOperation> revisionLog, NoteVisibility visibility,  List<NoteAccess> noteAccesses, int currentNoteVersionNumber, List<NoteVersion> noteVersions) {
        this.id = id;
        this.user = user;
        this.title = title;
        this.revisionLog = revisionLog != null ? revisionLog : new ArrayList<>();
        this.visibility = visibility;
        this.noteAccesses = noteAccesses != null ? noteAccesses : new ArrayList<>();
        this.currentNoteVersionNumber = currentNoteVersionNumber;
        this.noteVersions = noteVersions != null ? noteVersions : new ArrayList<>();
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
        return "Note{" +
                "id=" + id +
                ", user=" + user.getId() +
                ", title='" + title + '\'' +
                ", visibility=" + visibility +
                ", noteAccesses=" + noteAccesses +
                ", currentNoteVersionNumber=" + currentNoteVersionNumber +
                ", noteVersions=" + noteVersions +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}