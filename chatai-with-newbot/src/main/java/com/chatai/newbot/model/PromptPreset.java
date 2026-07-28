package com.chatai.newbot.model;

import lombok.Data;

/**
 * 用户自定义提示词预设（个人设置中维护）。
 * 每个用户可保存多条提示词，但最多只能启用其中 1 条；
 * 被启用的那条会在每次调用 LLM API 时作为 system 消息注入，等价于该用户的全局提示词。
 * 数据随 User 一同持久化（SQLite t_user.prompt_presets 列 / JSON 文件的 User 对象）。
 */
@Data
public class PromptPreset {
    private String id;        // 唯一ID (UUID)
    private String title;     // 提示词名称（便于用户区分，如 "严谨技术顾问"、"翻译"）
    private String content;   // 提示词内容（作为 system 消息注入）
    private boolean enabled;  // 是否启用（全部预设中最多 1 条为 true）
}
