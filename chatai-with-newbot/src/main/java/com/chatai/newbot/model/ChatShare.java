package com.chatai.newbot.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
    /** 分享时刻的消息快照 JSON；源会话变化或删除不会改变已分享内容 */
    @JsonIgnore
    private String snapshotJson;
    /** 可选访问密码的 BCrypt 摘要；绝不返回前端 */
    @JsonIgnore
    private String passwordHash;
    /** 访问密码的 AES-256-GCM 密文（ENC: 前缀）；仅分享创建者与管理员可见明文 */
    @JsonIgnore
    private String passwordEnc;
    /** 已成功读取次数 */
    private int accessCount;
    /** 最大读取次数，0 表示不限 */
    private int maxViews;
    /** 是否在生成快照时执行了敏感信息脱敏 */
    private boolean sanitized;
}
