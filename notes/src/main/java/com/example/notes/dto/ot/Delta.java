package com.example.notes.dto.ot;

import lombok.Getter;

import java.util.*;

@Getter
public class Delta {
    private List<Op> ops = new ArrayList<Op>();
    private UUID actorId;

    public Delta() {}

    public Delta(List<Op> ops, UUID actorId) {
        this.ops = ops;
        this.actorId = actorId;
    }

    public void push(Op newOp) {
        if (newOp.length() <= 0) return;

        if (!ops.isEmpty()) {
            Op lastOp = ops.get(ops.size() - 1);

            if (newOp.isInsert() && lastOp.isInsert() && newOp.getAttributes().equals(lastOp.getAttributes())) {
                String newText = (String) lastOp.getInsert() + (String) newOp.getInsert();
                lastOp.setInsert(newText);
                return;
            }

            if (newOp.isDelete() && lastOp.isDelete()) {
                lastOp.setDelete(lastOp.length() + newOp.length());
                return;
            }

            if (newOp.isRetain() && lastOp.isRetain() && newOp.getAttributes().equals(lastOp.getAttributes())) {
                lastOp.setRetain(lastOp.length() + newOp.length());
                return;
            }
        }

        ops.add(newOp);
    }

    public void insert(String text, Map<String, Object> attributes) {
        Op op = new Op();
        op.setInsert(text);
        op.setAttributes(attributes);
        this.push(op);
    }

    public void delete(int length) {
        Op op = new Op();
        op.setDelete(length);
        this.push(op);
    }

    public void retain(int length, Map<String, Object> attributes) {
        Op op = new Op();
        op.setRetain(length);
        op.setAttributes(attributes);
        this.push(op);
    }

    public Delta compact() {
        if (ops.isEmpty()) return this;

        Op lastOp = ops.get(ops.size() - 1);
        if (lastOp.isRetain() && lastOp.getAttributes().isEmpty()) {
            ops.remove(ops.size() - 1);
        }
        return this;
    }

    public Delta transform(Delta other, boolean priority) {
        DeltaIterator thisIterator = new DeltaIterator(this.ops);
        DeltaIterator otherIterator = new DeltaIterator(other.ops);
        Delta transformed = new Delta();

        while (thisIterator.hasNext() || otherIterator.hasNext()) {
            // SCENARIO 1: My delta has an Insert
            // Inserts always take precedence because they don't depend on existing text.
            if (thisIterator.peekType().equals(OpType.INSERT) && (priority || !otherIterator.peekType().equals(OpType.INSERT))) {
                transformed.push(thisIterator.next());
                continue;
            }

            // SCENARIO 2: Their delta has an Insert
            // If they inserted something, I need to "retain" (skip) over it
            // to keep my relative position in the document.
            if (otherIterator.peekType().equals(OpType.INSERT)) {
                transformed.retain(otherIterator.next().length(), null);
                continue;
            }

            // SCENARIO 3: Overlapping Retains/Deletes
            // We find the smallest common length to process a "segment"
            int length = Math.min(thisIterator.peekLength(), otherIterator.peekLength());
            Op thisOp = thisIterator.next(length);
            Op otherOp = otherIterator.next(length);

            if (thisOp.isDelete()) {
                // I deleted this part. Even if they formatted it, it's gone.
                // We don't push anything to the transformed delta (it stays a deletion).
                continue;
            }

            if (thisOp.isRetain()) {
                if (otherOp.isDelete()) {
                    // I wanted to keep/format this, but they deleted it.
                    // My intent is lost because the text is gone.
                } else {
                    // Both are Retains! This is where we handle formatting.
                    // We merge attributes (we will build this helper next).
                    Map<String, Object> attributes = transformAttributes(
                            thisOp.getAttributes(),
                            otherOp.getAttributes(),
                            priority
                    );
                    transformed.retain(length, attributes);
                }
            }
        }

        return transformed.compact();
    }

    public Delta compose(Delta other) {
        DeltaIterator thisIterator = new DeltaIterator(this.ops);
        DeltaIterator otherIterator = new DeltaIterator(other.ops);
        Delta result = new Delta();

        while (thisIterator.hasNext() || otherIterator.hasNext()) {
            // 1. If 'other' has an insert, it's a new addition that didn't exist in 'this'
            if (otherIterator.peekType().equals(OpType.INSERT)) {
                result.push(otherIterator.next());
                continue;
            }

            // 2. If 'this' has a delete, that content is gone and can't be affected by 'other'
            if (thisIterator.peekType().equals(OpType.DELETE)) {
                result.push(thisIterator.next());
                continue;
            }

            int length = Math.min(thisIterator.peekLength(), otherIterator.peekLength());
            Op thisOp = thisIterator.next(length);
            Op otherOp = otherIterator.next(length);

            if (otherOp.isRetain()) {
                Op newOp = new Op();
                Map<String, Object> attributes = composeAttributes(thisOp.getAttributes(), otherOp.getAttributes());
                newOp.setAttributes(attributes);

                if (thisOp.isInsert()) {
                    newOp.setInsert(thisOp.getInsert());
                } else {
                    newOp.setRetain(length);
                }

                result.push(newOp);
            } else if (otherOp.isDelete()) {
                if (!thisOp.isDelete()) {
                    result.push(otherOp);
                }
            }

        }
        return result.compact();
    }

    private Map<String, Object> composeAttributes(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Object> result = new HashMap<>(a);

        for (Map.Entry<String, Object> entry : b.entrySet()) {
            if (entry.getValue() == null) {
                result.remove(entry.getKey());
            } else {
                result.put(entry.getKey(), entry.getValue());
            }
        }

        return result.isEmpty() ? null : result;
    }

    private Map<String, Object> transformAttributes(Map<String, Object> a, Map<String, Object> b, boolean priority) {
        if (a == null || a.isEmpty()) return null; // If I have no attributes, nothing to transform
        if (b == null || b.isEmpty()) return a;    // If they have none, mine are unchanged

        Map<String, Object> result = new HashMap<String, Object>();

        for (String key: a.keySet()) {
            if (b.containsKey(key)) {
                if (priority) {
                    result.put(key, a.get(key));
                }
            } else {
                result.put(key, a.get(key));
            }
        }

        return result.isEmpty() ? null : result;
    }
}
