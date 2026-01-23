package com.example.notes.shared.model;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class MessageOutPayloadWrapper<T> {
    private String type;
    private T payload;

    public MessageOutPayloadWrapper(String type, T payload) {
        this.type = type;
        this.payload = payload;
    }

    public MessageOutPayloadWrapper() {}

}
