package com.crowninteractive.notes.dto.ot;

import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

@Setter
@Getter
public class Op {
    private Object insert;
    private Integer delete;
    private Object retain;
    private Map<String, Object> attributes;

    public Op() {}

    public boolean isInsert() {
        return insert != null;
    }

    public boolean isDelete() {
        return delete != null;
    }

    public boolean isRetain() {
        return retain != null;
    }

    public Integer length() {
        if (delete != null) return delete;
        if (retain instanceof Integer) return (Integer) retain;
        if (retain != null) return 1;
        if (insert instanceof String) return ((String) insert).length();
        if (insert != null) return 1;
        return 0;
    }

    @Override
    public String toString() {
        return "Op{" +
                "insert=" + insert +
                ", delete=" + delete +
                ", retain=" + retain +
                ", attributes=" + attributes +
                '}';
    }
}
