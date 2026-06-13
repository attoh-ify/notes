package com.crowninteractive.notes.entities.noteAccess;

import com.crowninteractive.notes.entities.note.Note;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

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
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "note_access_id", nullable = false, unique = true, updatable = false)
    private String noteAccessId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "note_id", nullable = false)
    private Note note;

    @Column(name = "email", nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private NoteAccessRole role;

    public NoteAccess() {}

    public NoteAccess(Long id, String noteAccessId, Note note, String email, NoteAccessRole role) {
        this.id = id;
        this.noteAccessId = noteAccessId;
        this.note = note;
        this.email = email;
        this.role = role;
    }

}
