package com.chatai.newbot.model;

import lombok.Data;

/**
 * 系统公告记录：支持公告期（生效/失效时间）与历史公告管理，
 * 同一时刻最多一条公告处于启用状态（发布/重新生效时自动下线其它公告）
 */
@Data
public class Announcement {
    private String id;              // 公告ID（UUID去横线）
    private String title;           // 公告标题（用户端弹窗标题展示，空=默认“系统公告”）
    private String content;         // 公告正文
    private String startAt;         // 公告期开始时间 yyyy-MM-dd HH:mm:ss，null/空=立即生效
    private String endAt;           // 公告期结束时间 yyyy-MM-dd HH:mm:ss，null/空=长期有效
    private boolean enabled;        // 是否启用（false=已手动下线）
    private String createdAt;       // 创建时间 yyyy-MM-dd HH:mm:ss
    private String updatedAt;       // 最后发布/生效时间 yyyy-MM-dd HH:mm:ss（变化后用户端会重新弹窗提醒）
}
