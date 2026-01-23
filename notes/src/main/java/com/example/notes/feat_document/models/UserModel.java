package com.example.notes.feat_document.models;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class UserModel {
    private String userId;

    public UserModel() {}

    public UserModel(String userId) {
        this.userId = userId;
    }
}
