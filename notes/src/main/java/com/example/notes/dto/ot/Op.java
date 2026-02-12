package com.example.notes.dto.ot;

import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

@Setter
public class Op {
    @Getter
    private Object insert;
    private Integer delete;
    private Integer retain;
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
        if (retain != null) return retain;
        if (insert instanceof String) return ((String) insert).length();
        if (insert != null) return 1;
        return 0;
    }

    public Map<String, Object> getAttributes() {
        return attributes != null ? attributes : new HashMap<>();
    }
}
