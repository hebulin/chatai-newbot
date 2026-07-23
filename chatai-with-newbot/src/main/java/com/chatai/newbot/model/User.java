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
    /** 用户自定义全局提示词（System Prompt）。每次调用 LLM API 时作为 system 消息置于消息列表首位，优先级最高；为空时使用系统默认提示词 */
    private String systemPrompt;

    @JsonIgnore
    public boolean isAdmin() {
        return "admin".equals(role);
    }
}
