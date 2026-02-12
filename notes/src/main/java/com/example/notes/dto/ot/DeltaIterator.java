package com.example.notes.dto.ot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class DeltaIterator {
    private List<Op> ops;
    private int index = 0;
    private int offset = 0;

    public DeltaIterator(List<Op> ops) {
        this.ops = ops != null ? ops : new ArrayList<>();
    }

    public boolean hasNext() {
        return index < ops.size();
    }

    public int peekLength() {
        if (!hasNext()) return Integer.MAX_VALUE;
        return ops.get(index).length() - offset;
    }

    public OpType peekType() {
        if (!hasNext()) return OpType.NONE;
        Op op = ops.get(index);
        if (op.isInsert()) return OpType.INSERT;
        if (op.isDelete()) return OpType.DELETE;
        return OpType.RETAIN;
    }

    public Op next(int length) {
        if (!hasNext()) return null;

        Op currentOp = ops.get(index);
        int opLength = currentOp.length();
        int actualLimit = Math.min(length, opLength - offset);

        Op slice = new Op();
        if (currentOp.getAttributes() != null) {
            slice.setAttributes(new HashMap<>(currentOp.getAttributes()));
        }

        if (currentOp.isDelete()) {
            slice.setDelete(actualLimit);
        } else if (currentOp.isRetain()) {
            slice.setRetain(actualLimit);
        } else if (currentOp.isInsert()) {
            if (currentOp.getInsert() instanceof String) {
                String text = (String) currentOp.getInsert();
                slice.setInsert(text.substring(offset, offset + actualLimit));
            } else {
                slice.setInsert(currentOp.getInsert());
            }
        }

        offset += actualLimit;
        if (offset >= opLength) {
            offset = 0;
            index++;
        }

        return slice;
    }

    public Op next() {
        return next(peekLength());
    }
}
