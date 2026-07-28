package com.chatai.newbot.model;

import lombok.Data;

/**
 * 会话分享记录：将某个会话生成只读链接，任何人凭链接可查看（无需登录）
 */
@Data
public class ChatShare {
    private String id;              // 分享码（UUID去横线，作为只读链接标识）
    private String chatId;          // 被分享的会话ID
    private String userId;          // 分享者用户ID
    private String userName;        // 分享者用户名
    private String title;           // 会话标题快照（分享时刻的标题）
    private String createdAt;       // 创建时间 yyyy-MM-dd HH:mm:ss
    private String expiresAt;       // 过期时间 yyyy-MM-dd HH:mm:ss，null/空=永久有效
}
