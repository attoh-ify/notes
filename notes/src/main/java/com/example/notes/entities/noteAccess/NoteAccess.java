package com.example.notes.entities.noteAccess;

import com.example.notes.entities.note.Note;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Setter
@Getter
@Entity
@Table(
        name = "note_access",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"note_id", "email"})
        }
)
public class NoteAccess {
    @Id
    @Column(name = "id", updatable = false, nullable = false)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "note_id", nullable = false)
    private Note note;

    @Column(name = "email", nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private NoteAccessRole role;

    public NoteAccess() {}

    public NoteAccess(UUID id, Note note, String email, NoteAccessRole role) {
        this.id = id;
        this.note = note;
        this.email = email;
        this.role = role;
    }

}
