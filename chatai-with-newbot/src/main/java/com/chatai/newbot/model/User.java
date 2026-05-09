package com.chatai.newbot.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class User {
    private String id;
    private String username;
    private String password;
    private String role; // "admin" or "user"
    private String createdAt;
    private String lastLoginAt;
    private String lastLoginIp;
    private String lastLoginBrowser;
    private List<String> allowedModelIds = new ArrayList<>(); // 特别授权的模型ID列表

    @JsonIgnore
    public boolean isAdmin() {
        return "admin".equals(role);
    }
}
