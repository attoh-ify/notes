package com.example.notes.dto.ot;

import java.util.*;
import java.util.function.*;

public class Delta {

    // --- Embed handler registry ---

    public interface EmbedHandler {
        Object compose(Object a, Object b, boolean keepNull);
        Object invert(Object a, Object b);
        Object transform(Object a, Object b, boolean priority);
    }

    private static final Map<String, EmbedHandler> handlers = new HashMap<>();

    public static void registerEmbed(String embedType, EmbedHandler handler) {
        handlers.put(embedType, handler);
    }

    public static void unregisterEmbed(String embedType) {
        handlers.remove(embedType);
    }

    private static EmbedHandler getHandler(String embedType) {
        EmbedHandler h = handlers.get(embedType);
        if (h == null) throw new IllegalStateException("no handler for embed type: " + embedType);
        return h;
    }

    // --- Embed type extraction ---

    private static Object[] getEmbedTypeAndData(Object a, Object b) {
        if (!(a instanceof Map))
            throw new IllegalArgumentException("cannot retain a " + (a == null ? "null" : a.getClass()));
        if (!(b instanceof Map))
            throw new IllegalArgumentException("cannot retain a " + (b == null ? "null" : b.getClass()));

        Map<?, ?> aMap = (Map<?, ?>) a;
        Map<?, ?> bMap = (Map<?, ?>) b;
        String embedType = (String) aMap.keySet().iterator().next();
        if (embedType == null || !embedType.equals(bMap.keySet().iterator().next())) {
            throw new IllegalArgumentException(
                    "embed types not matched: " + embedType + " != " + bMap.keySet().iterator().next());
        }
        return new Object[]{ embedType, aMap.get(embedType), bMap.get(embedType) };
    }

    // --- Fields ---

    public List<Op> ops;

    public Delta() {
        this.ops = new ArrayList<>();
    }

    public Delta(List<Op> ops) {
        this.ops = ops != null ? ops : new ArrayList<>();
    }

    // --- Mutation helpers ---

    public Delta insert(Object arg, Map<String, Object> attributes) {
        if (arg instanceof String && ((String) arg).isEmpty()) return this;
        Op newOp = new Op();
        newOp.setInsert(arg);
        if (attributes != null && !attributes.isEmpty()) {
            newOp.setAttributes(attributes);
        }
        return push(newOp);
    }

    public Delta delete(int length) {
        if (length <= 0) return this;
        Op op = new Op();
        op.setDelete(length);
        return push(op);
    }

    public Delta retain(Object length, Map<String, Object> attributes) {
        if (length instanceof Integer && (Integer) length <= 0) return this;
        Op newOp = new Op();
        newOp.setRetain(length);
        if (attributes != null && !attributes.isEmpty()) {
            newOp.setAttributes(attributes);
        }
        return push(newOp);
    }

    public Delta push(Op newOp) {
        newOp = deepCopyOp(newOp);

        int index = ops.size();
        Op lastOp = index > 0 ? ops.get(index - 1) : null;

        if (lastOp != null) {
            // Merge consecutive deletes
            if (newOp.isDelete() && lastOp.isDelete()) {
                ops.get(index - 1).setDelete(lastOp.getDelete() + newOp.getDelete());
                return this;
            }
            // Always insert before delete at same position
            if (lastOp.isDelete() && newOp.isInsert()) {
                index -= 1;
                lastOp = index > 0 ? ops.get(index - 1) : null;
                if (lastOp == null) {
                    ops.add(0, newOp);
                    return this;
                }
            }
            // Merge inserts or retains with matching attributes
            if (Objects.deepEquals(newOp.getAttributes(), lastOp.getAttributes())) {
                if (newOp.getInsert() instanceof String && lastOp.getInsert() instanceof String) {
                    Op merged = new Op();
                    merged.setInsert((String) lastOp.getInsert() + (String) newOp.getInsert());
                    if (newOp.getAttributes() != null) merged.setAttributes(newOp.getAttributes());
                    ops.set(index - 1, merged);
                    return this;
                } else if (newOp.getRetain() instanceof Integer && lastOp.getRetain() instanceof Integer) {
                    Op merged = new Op();
                    merged.setRetain((Integer) lastOp.getRetain() + (Integer) newOp.getRetain());
                    if (newOp.getAttributes() != null) merged.setAttributes(newOp.getAttributes());
                    ops.set(index - 1, merged);
                    return this;
                }
            }
        }

        if (index == ops.size()) {
            ops.add(newOp);
        } else {
            ops.add(index, newOp);
        }
        return this;
    }

    public Delta chop() {
        if (!ops.isEmpty()) {
            Op lastOp = ops.get(ops.size() - 1);
            if (lastOp.getRetain() instanceof Integer && lastOp.getAttributes() == null) {
                ops.remove(ops.size() - 1);
            }
        }
        return this;
    }

    // --- Iteration helpers ---

    public List<Op> filter(Predicate<Op> predicate) {
        List<Op> result = new ArrayList<>();
        for (Op op : ops) if (predicate.test(op)) result.add(op);
        return result;
    }

    public void forEach(BiConsumer<Op, Integer> consumer) {
        for (int i = 0; i < ops.size(); i++) consumer.accept(ops.get(i), i);
    }

    public <T> List<T> map(BiFunction<Op, Integer, T> fn) {
        List<T> result = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) result.add(fn.apply(ops.get(i), i));
        return result;
    }

    public int changeLength() {
        int length = 0;
        for (Op op : ops) {
            if (op.isInsert()) length += op.length();
            else if (op.isDelete()) length -= op.getDelete();
        }
        return length;
    }

    public int length() {
        int len = 0;
        for (Op op : ops) len += op.length();
        return len;
    }

    public Delta slice(int start, int end) {
        List<Op> result = new ArrayList<>();
        DeltaIterator iter = new DeltaIterator(ops);
        int index = 0;
        while (index < end && iter.hasNext()) {
            Op nextOp;
            if (index < start) {
                nextOp = iter.next(start - index);
            } else {
                nextOp = iter.next(end - index);
                result.add(nextOp);
            }
            index += nextOp.length();
        }
        return new Delta(result);
    }

    // --- Compose ---

    public Delta compose(Delta other) {
        DeltaIterator thisIter = new DeltaIterator(this.ops);
        DeltaIterator otherIter = new DeltaIterator(other.ops);
        List<Op> resultOps = new ArrayList<>();

        Op firstOther = otherIter.peek();
        if (firstOther != null && firstOther.getRetain() instanceof Integer && firstOther.getAttributes() == null) {
            int firstLeft = (Integer) firstOther.getRetain();
            while (thisIter.peekType().equals(OpType.INSERT) && thisIter.peekLength() <= firstLeft) {
                firstLeft -= thisIter.peekLength();
                resultOps.add(thisIter.next());
            }
            int consumed = (Integer) firstOther.getRetain() - firstLeft;
            if (consumed > 0) otherIter.next(consumed);
        }

        Delta delta = new Delta(resultOps);
        while (thisIter.hasNext() || otherIter.hasNext()) {
            if (otherIter.peekType().equals(OpType.INSERT)) {
                delta.push(otherIter.next());
            } else if (thisIter.peekType().equals(OpType.DELETE)) {
                delta.push(thisIter.next());
            } else {
                int length = Math.min(thisIter.peekLength(), otherIter.peekLength());
                Op thisOp = thisIter.next(length);
                Op otherOp = otherIter.next(length);

                if (otherOp.isRetain()) {
                    Op newOp = new Op();
                    if (thisOp.getRetain() instanceof Integer) {
                        newOp.setRetain((otherOp.getRetain() instanceof Integer) ? length : otherOp.getRetain());
                    } else {
                        if (otherOp.getRetain() instanceof Integer) {
                            if (thisOp.getRetain() == null) newOp.setInsert(thisOp.getInsert());
                            else newOp.setRetain(thisOp.getRetain());
                        } else {
                            OpType action = (thisOp.getRetain() == null) ? OpType.INSERT : OpType.RETAIN;
                            Object source = action.equals(OpType.INSERT) ? thisOp.getInsert() : thisOp.getRetain();
                            Object[] parts = getEmbedTypeAndData(source, otherOp.getRetain());
                            String embedType = (String) parts[0];
                            EmbedHandler handler = getHandler(embedType);
                            Object composed = handler.compose(parts[1], parts[2], action.equals(OpType.RETAIN));
                            if (action.equals(OpType.INSERT)) newOp.setInsert(Map.of(embedType, composed));
                            else newOp.setRetain(Map.of(embedType, composed));
                        }
                    }
                    Map<String, Object> attrs = AttributeMap.compose(
                            thisOp.getAttributes(), otherOp.getAttributes(), thisOp.getRetain() instanceof Integer);
                    if (attrs != null) newOp.setAttributes(attrs);
                    delta.push(newOp);

                    // Optimization: rest of other is just retain
                    if (!otherIter.hasNext() && Objects.deepEquals(delta.ops.get(delta.ops.size() - 1), newOp)) {
                        Delta rest = new Delta(thisIter.rest());
                        return delta.concat(rest).chop();
                    }

                } else if (otherOp.isDelete() &&
                        (thisOp.getRetain() instanceof Integer ||
                                (thisOp.getRetain() instanceof Map && thisOp.isRetain()))) {
                    delta.push(otherOp);
                }
            }
        }
        return delta.chop();
    }

    // --- Concat ---

    public Delta concat(Delta other) {
        Delta delta = new Delta(new ArrayList<>(this.ops));
        if (!other.ops.isEmpty()) {
            delta.push(other.ops.get(0));
            delta.ops.addAll(other.ops.subList(1, other.ops.size()));
        }
        return delta;
    }

    // --- Invert ---

    public Delta invert(Delta base) {
        Delta inverted = new Delta();
        int[] baseIndex = {0};
        for (Op op : this.ops) {
            if (op.isInsert()) {
                inverted.delete(op.length());
            } else if (op.getRetain() instanceof Integer && op.getAttributes() == null) {
                inverted.retain(op.getRetain(), null);
                baseIndex[0] += (Integer) op.getRetain();
            } else if (op.isDelete() || op.getRetain() instanceof Integer) {
                int length = op.isDelete() ? op.getDelete() : (Integer) op.getRetain();
                Delta slice = base.slice(baseIndex[0], baseIndex[0] + length);
                for (Op baseOp : slice.ops) {
                    if (op.isDelete()) {
                        inverted.push(baseOp);
                    } else if (op.isRetain() && op.getAttributes() != null) {
                        inverted.retain(
                                baseOp.length(),
                                AttributeMap.invert(op.getAttributes(), baseOp.getAttributes()));
                    }
                }
                baseIndex[0] += length;
            } else if (op.getRetain() instanceof Map && op.isRetain()) {
                Delta slice = base.slice(baseIndex[0], baseIndex[0] + 1);
                Op baseOp = new DeltaIterator(slice.ops).next();
                Object[] parts = getEmbedTypeAndData(op.getRetain(), baseOp.getInsert());
                String embedType = (String) parts[0];
                EmbedHandler handler = getHandler(embedType);
                inverted.retain(
                        Map.of(embedType, handler.invert(parts[1], parts[2])),
                        AttributeMap.invert(op.getAttributes(), baseOp.getAttributes()));
                baseIndex[0] += 1;
            }
        }
        return inverted.chop();
    }

    // --- Transform ---

    public Delta transform(Delta other, boolean priority) {
        DeltaIterator thisIter = new DeltaIterator(this.ops);
        DeltaIterator otherIter = new DeltaIterator(other.ops);
        Delta delta = new Delta();

        while (thisIter.hasNext() || otherIter.hasNext()) {
            if (thisIter.peekType().equals(OpType.INSERT) &&
                    (priority || !otherIter.peekType().equals(OpType.INSERT))) {
                delta.retain(thisIter.next().length(), null);
            } else if (otherIter.peekType().equals(OpType.INSERT)) {
                delta.push(otherIter.next());
            } else {
                int length = Math.min(thisIter.peekLength(), otherIter.peekLength());
                Op thisOp = thisIter.next(length);
                Op otherOp = otherIter.next(length);

                if (thisOp.isDelete()) {
                    continue; // our delete makes their op redundant
                } else if (otherOp.isDelete()) {
                    delta.push(otherOp);
                } else {
                    Object thisData = thisOp.getRetain();
                    Object otherData = otherOp.getRetain();
                    Object transformedData = (otherData instanceof Map) ? otherData : length;

                    if (thisData instanceof Map && otherData instanceof Map) {
                        String embedType = (String) ((Map<?, ?>) thisData).keySet().iterator().next();
                        if (embedType.equals(((Map<?, ?>) otherData).keySet().iterator().next())) {
                            EmbedHandler handler = handlers.get(embedType);
                            if (handler != null) {
                                transformedData = Map.of(embedType, handler.transform(
                                        ((Map<?, ?>) thisData).get(embedType),
                                        ((Map<?, ?>) otherData).get(embedType),
                                        priority));
                            }
                        }
                    }

                    delta.retain(transformedData,
                            AttributeMap.transform(thisOp.getAttributes(), otherOp.getAttributes(), priority));
                }
            }
        }
        return delta.chop();
    }

    public int transformPosition(int index, boolean priority) {
        DeltaIterator iter = new DeltaIterator(this.ops);
        int offset = 0;
        while (iter.hasNext() && offset <= index) {
            int length = iter.peekLength();
            OpType type = iter.peekType();
            iter.next();
            if (type.equals(OpType.DELETE)) {
                index -= Math.min(length, index - offset);
            } else if (type.equals(OpType.INSERT) && (offset < index || !priority)) {
                index += length;
                offset += length;
            } else {
                offset += length;
            }
        }
        return index;
    }

    // --- eachLine ---

    public void eachLine(TriFunction<Delta, Map<String, Object>, Integer, Boolean> predicate, String newline) {
        DeltaIterator iter = new DeltaIterator(this.ops);
        Delta line = new Delta();
        int i = 0;
        while (iter.hasNext()) {
            if (!iter.peekType().equals(OpType.INSERT)) return;
            Op thisOp = iter.peek();
            int start = thisOp.length() - iter.peekLength();
            int index = (thisOp.getInsert() instanceof String)
                    ? ((String) thisOp.getInsert()).indexOf(newline, start) - start
                    : -1;
            if (index < 0) {
                line.push(iter.next());
            } else if (index > 0) {
                line.push(iter.next(index));
            } else {
                Op next = iter.next(1);
                Boolean result = predicate.apply(line, next.getAttributes() != null ? next.getAttributes() : new HashMap<>(), i);
                if (Boolean.FALSE.equals(result)) return;
                i++;
                line = new Delta();
            }
        }
        if (line.length() > 0) {
            predicate.apply(line, new HashMap<>(), i);
        }
    }

    // --- Utilities ---

    @FunctionalInterface
    public interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }

    private static Op deepCopyOp(Op op) {
        Op copy = new Op();

        if (op.getInsert() instanceof Map) {
            copy.setInsert(new HashMap<>((Map<?, ?>) op.getInsert()));
        } else {
            copy.setInsert(op.getInsert()); // strings are immutable; maps should be deep-copied in real use
        }

        copy.setDelete(op.getDelete());

        if (op.getRetain() instanceof Map) {
            copy.setRetain(new HashMap<>((Map<?, ?>) op.getRetain()));
        } else {
            copy.setRetain(op.getRetain());
        }

        if (op.getAttributes() != null) {
            copy.setAttributes(new HashMap<>(op.getAttributes()));
        }
        return copy;
    }
}