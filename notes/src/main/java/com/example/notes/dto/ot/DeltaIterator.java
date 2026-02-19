package com.example.notes.dto.ot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DeltaIterator {
    private List<Op> ops;
    private int index;
    private int offset;

    public DeltaIterator(List<Op> ops) {
        this.ops = ops != null ? ops : new ArrayList<>();
        this.index = 0;
        this.offset = 0;
    }

    public boolean hasNext() {
        return peekLength() < Integer.MAX_VALUE;
    }

    public Op next() {
        return next(Integer.MAX_VALUE);
    }

    public Op next(int length) {
        if (index >= ops.size()) {
            // Return a virtual trailing retain instead of null
            Op trailing = new Op();
            trailing.setRetain(Integer.MAX_VALUE);
            return trailing;
        }

        Op nextOp = ops.get(index);
        int opLength = nextOp.length();
        int currentOffset = this.offset;

        // Determine how much we are actually taking
        int take = Math.min(length, opLength - currentOffset);

        // Update pointers
        if (take >= opLength - currentOffset) {
            this.index++;
            this.offset = 0;
        } else {
            this.offset += take;
        }

        // Create the slice
        Op slice = new Op();
        if (nextOp.getAttributes() != null) {
            slice.setAttributes(nextOp.getAttributes());
        }

        if (nextOp.isDelete()) {
            slice.setDelete(take);
        } else if (nextOp.getRetain() instanceof Integer) {
            // Check if it's an embed retain or number
            slice.setRetain(take);
        } else if (nextOp.getRetain() instanceof Map) {
            slice.setRetain(nextOp.getRetain());
        } else if (nextOp.getInsert() instanceof String) {
            slice.setInsert(((String) nextOp.getInsert()).substring(currentOffset, currentOffset + take));
        } else {
            slice.setInsert(nextOp.getInsert());
        }
        return slice;
    }

    public Op peek() {
        return index < ops.size() ? ops.get(index) : null;
    }

    public int peekLength() {
        if (index < ops.size()) {
            return ops.get(index).length() - offset;
        }
        return Integer.MAX_VALUE;
    }

    public List<Op> rest() {
        if (!hasNext()) return new ArrayList<>();

        List<Op> result = new ArrayList<>();
        if (offset == 0) {
            result.addAll(ops.subList(index, ops.size()));
        } else {
            int oldOffset = this.offset;
            int oldIndex = this.index;
            result.add(this.next());
            result.addAll(ops.subList(index, ops.size()));
            this.offset = oldOffset;
            this.index = oldIndex;
        }
        return result;
    }

    public OpType peekType() {
        if (!hasNext()) return OpType.NONE;
        Op op = ops.get(index);
        if (op.isInsert()) return OpType.INSERT;
        if (op.isDelete()) return OpType.DELETE;
        return OpType.RETAIN;
    }
}
