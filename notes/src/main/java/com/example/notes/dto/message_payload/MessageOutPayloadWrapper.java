package com.example.notes.dto.message_payload;

import com.example.notes.entities.MessageType;
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
