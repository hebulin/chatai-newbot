package com.chatai.newbot.model;

import lombok.Data;

/**
 * 聊天消息附件（文本类文档）：上传时已在服务端解析为纯文本落盘，
 * 消息中仅保存原始文件名（展示用）与解析文本的引用 URL，
 * 模型调用前由后端读取文本合并进消息内容，因此不依赖模型多模态能力
 */
@Data
public class ChatAttachment {
    /** 原始文件名（仅用于展示与提示词标注） */
    private String name;
    /** 解析文本的引用 URL（/api/files/doc/{yyyyMM}/{filename}） */
    private String url;
}
