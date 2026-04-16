package com.example.notes.dto.note;

// ─── OpReference ──────────────────────────────────────────────────────────────
//
// A reference to one specific component (insert / retain / delete) inside one
// specific TextOperation's delta.
//
// Why we need this: a single logical suggestion (e.g. "Alex inserted Hello World")
// may be produced by multiple server operations if the text was typed across
// several keystrokes that the server committed separately. Each contributing
// op+component pair is tracked so we can tell the backend exactly which pieces
// to mark as committed when the reviewer accepts/rejects.
// ──────────────────────────────────────────────────────────────────────────────
public record OpReference(
        String opId,
        Integer componentIndex
) {}
