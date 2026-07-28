package com.chatai.newbot.model;

import lombok.Data;

import java.util.List;

@Data
public class NewBotMessage {
    private String role;
    private String content;
    private List<String> images; // 图片 base64 列表（多模态时使用，格式: data:image/png;base64,...）
    private List<ChatAttachment> attachments; // 文档附件列表（已解析为文本，后端合并进 content，不依赖多模态）
}