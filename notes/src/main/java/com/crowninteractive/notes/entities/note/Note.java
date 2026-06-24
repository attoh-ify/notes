package com.crowninteractive.notes.entities.note;

import com.crowninteractive.notes.converter.TextOperationListConverter;
import com.crowninteractive.notes.entities.noteAccess.NoteAccess;
import com.crowninteractive.notes.entities.noteVersion.NoteVersion;
import com.crowninteractive.notes.entities.user.User;
import com.crowninteractive.notes.dto.ot.TextOperation;
import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
@Entity
@Table(name = "notes")
public class Note {
    @Id
    @Column(name = "id", updatable = false, nullable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "note_id", nullable = false, unique = true, updatable = false)
    private String noteId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "title", nullable = false)
    private String title;

    @Convert(converter = TextOperationListConverter.class)
    @Lob
    @Column(name = "revision_log", nullable = false, columnDefinition = "TEXT")
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
    
    @Column(name = "is_reviewing", nullable = false)
    private boolean isReviewing = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Note() {}

    public Note(Long id, String noteId, User user, String title, List<TextOperation> revisionLog, NoteVisibility visibility,  List<NoteAccess> noteAccesses, int currentNoteVersionNumber, List<NoteVersion> noteVersions, boolean isReviewing) {
        this.id = id;
        this.noteId = noteId;
        this.user = user;
        this.title = title;
        this.revisionLog = revisionLog != null ? revisionLog : new ArrayList<>();
        this.visibility = visibility;
        this.noteAccesses = noteAccesses != null ? noteAccesses : new ArrayList<>();
        this.currentNoteVersionNumber = currentNoteVersionNumber;
        this.noteVersions = noteVersions != null ? noteVersions : new ArrayList<>();
        this.isReviewing = isReviewing;
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
                ", noteId=" + noteId +
                ", user=" + user.getId() +
                ", title='" + title + '\'' +
                ", visibility=" + visibility +
                ", noteAccesses=" + noteAccesses +
                ", currentNoteVersionNumber=" + currentNoteVersionNumber +
                ", noteVersions=" + noteVersions +
                ", isReviewing=" + isReviewing +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}