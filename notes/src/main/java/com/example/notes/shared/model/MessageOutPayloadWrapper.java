package com.example.notes.shared.model;

import com.example.notes.shared.message_pusher.MessageType;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class MessageOutPayloadWrapper<T> {
    private MessageType type;
    private T payload;

    public MessageOutPayloadWrapper(MessageType type, T payload) {
        this.type = type;
        this.payload = payload;
    }

    public MessageOutPayloadWrapper() {}

}
