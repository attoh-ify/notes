package com.crowninteractive.notes.dto.ot;

import java.util.*;

public class AttributeMap {
    public static Map<String, Object> compose(Map<String, Object> a, Map<String, Object> b, Boolean keepNull) {
        if (a == null) { a = new HashMap<>(); }
        if (b == null) { b = new HashMap<>(); }

        Map<String, Object> attributes = new HashMap<>(b);

        if (!keepNull) {
            attributes.entrySet().removeIf(e -> e.getValue() == null);
        }

        for (Map.Entry<String, Object> entry : a.entrySet()) {
            if (entry.getValue() != null && !b.containsKey(entry.getKey())) {
                attributes.put(entry.getKey(), entry.getValue());
            }
        }

        return attributes.isEmpty() ? null : attributes;
    }

    public static Map<String, Object> diff(Map<String, Object> a, Map<String, Object> b) {
        if (a == null) a = new HashMap<>();
        if (b == null) b = new HashMap<>();

        Set<String> keys = new HashSet<>(a.keySet());
        keys.addAll(b.keySet());

        Map<String, Object> attributes = new HashMap<>();

        for (String key : keys) {
            Object aValue = a.get(key);
            Object bValue = b.get(key);
            // If values are different, the diff is the target value (b)
            if (!Objects.deepEquals(aValue, bValue)) {
                attributes.put(key, b.containsKey(key) ? bValue : null);
            }
        }

        return attributes.isEmpty() ? null : attributes;
    }

    public static Map<String, Object> invert(Map<String, Object> attr, Map<String, Object> base) {
        if (attr == null) attr = new HashMap<>();
        if (base == null) base = new HashMap<>();

        Map<String, Object> baseInverted = new HashMap<>();

        // If base has it and it changed, revert to base value
        for (Map.Entry<String, Object> entry : base.entrySet()) {
            String key = entry.getKey();
            Object baseValue = entry.getValue();
            Object attrVal = attr.get(key);
            if (!Objects.deepEquals(baseValue, attrVal) && attr.containsKey(key)) {
                baseInverted.put(key, baseValue);
            }
        }

        // If base didn't have it but it was added in attr, null it out to remove it
        for (Map.Entry<String, Object> entry : attr.entrySet()) {
            String key = entry.getKey();
            Object baseValue = entry.getValue();
            Object attrVal = attr.get(key);
            if (!Objects.deepEquals(baseValue, attrVal) && !base.containsKey(key)) {
                baseInverted.put(key, null);
            }
        }

        return baseInverted;
    }

    public static Map<String, Object> transform(Map<String, Object> a, Map<String, Object> b, Boolean priority) {
        if (a == null) return b;
        if (b == null) return null;

        if (!priority) {
            return b;
        }

        Map<String, Object> attributes = new HashMap<>();

        for (Map.Entry<String, Object> entry : b.entrySet()) {
            if (!a.containsKey(entry.getKey())) {
                attributes.put(entry.getKey(), entry.getValue());
            }
        }

        return attributes.isEmpty() ? null : attributes;
    }
}