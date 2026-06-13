package com.crowninteractive.notes.utils;

import com.crowninteractive.notes.dto.ot.Delta;
import com.crowninteractive.notes.dto.ot.Op;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public final class QuillDeltaUtils {
    private QuillDeltaUtils() {}

    public static Delta emptyDocument() {
        return new Delta().insert("\n", null);
    }

    public static Delta ensureTerminalNewline(Delta delta) {
        Delta out = copyDelta(delta);

        if (out.ops == null || out.ops.isEmpty()) {
            return emptyDocument();
        }

        Map<String, Object> trailingAttrs = removeTrailingFormatRetains(out);

        if (endsWithNewline(out)) {
            if (!trailingAttrs.isEmpty()) {
                return applyAttrsToLastCharacter(out, trailingAttrs);
            }

            return out.chop();
        }

        out.insert(
                "\n",
                trailingAttrs.isEmpty() ? null : trailingAttrs
        );

        return out.chop();
    }

    private static Delta copyDelta(Delta delta) {
        Delta copy = new Delta();

        if (delta == null || delta.ops == null) {
            copy.ops = new ArrayList<>();
            return copy;
        }

        copy.ops = new ArrayList<>(delta.ops);
        return copy;
    }

    private static boolean endsWithNewline(Delta delta) {
        if (delta == null || delta.ops == null) return false;

        for (int i = delta.ops.size() - 1; i >= 0; i--) {
            Op op = delta.ops.get(i);

            if (op == null) continue;

            if (op.isInsert() && op.getInsert() instanceof String text) {
                return text.endsWith("\n");
            }

            if (op.isInsert()) {
                return false;
            }
        }

        return false;
    }

    private static Map<String, Object> removeTrailingFormatRetains(Delta delta) {
        Map<String, Object> attrs = new LinkedHashMap<>();

        if (delta == null || delta.ops == null) return attrs;

        while (!delta.ops.isEmpty()) {
            Op last = delta.ops.get(delta.ops.size() - 1);

            if (
                    last == null ||
                            !last.isRetain() ||
                            last.getAttributes() == null ||
                            last.getAttributes().isEmpty()
            ) {
                break;
            }

            Object retain = last.getRetain();

            boolean isRetainOne =
                    retain instanceof Integer value && value == 1;

            if (!isRetainOne) {
                break;
            }

            attrs.putAll(last.getAttributes());
            delta.ops.remove(delta.ops.size() - 1);
        }

        return attrs;
    }

    private static Delta applyAttrsToLastCharacter(
            Delta delta,
            Map<String, Object> attrs
    ) {
        int length = delta.length();

        if (length <= 0) {
            return emptyDocument().compose(new Delta().retain(1, attrs));
        }

        Delta overlay = new Delta();

        if (length > 1) {
            overlay.retain(length - 1, null);
        }

        overlay.retain(1, attrs);

        return delta.compose(overlay).chop();
    }
}