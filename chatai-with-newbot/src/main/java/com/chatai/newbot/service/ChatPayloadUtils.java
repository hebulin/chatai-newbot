package com.chatai.newbot.service;

import java.util.List;
import java.util.Map;

/**
 * 会话消息内容的纯函数工具（标题/预览/片段构建）。
 * 从 ChatHistoryService 与 ShareController 的重复实现中抽取，
 * 供会话存储、搜索、分享等多处复用；无任何外部依赖，可独立单元测试。
 */
public final class ChatPayloadUtils {

    private ChatPayloadUtils() {
    }

    /**
     * 生成会话标题：取第一条用户消息前 20 字（与前端侧边栏标题规则一致）
     * @param msgs 消息列表
     * @return 标题，无用户消息时返回"新会话"
     */
    public static String buildChatTitle(List<Map<String, Object>> msgs) {
        if (msgs == null) return "新会话";
        for (Map<String, Object> m : msgs) {
            if ("user".equals(m.get("role")) && m.get("content") instanceof String s && !s.isEmpty()) {
                return s.length() > 20 ? s.substring(0, 20) : s;
            }
        }
        return "新会话";
    }

    /**
     * 生成会话预览：首条用户消息前 300 字，供侧边栏标题回退与模糊搜索
     * @param msgs 消息列表
     * @return 预览文本，无用户消息时返回空串
     */
    public static String buildChatPreview(List<Map<String, Object>> msgs) {
        if (msgs == null) return "";
        for (Map<String, Object> m : msgs) {
            if ("user".equals(m.get("role")) && m.get("content") instanceof String c && !c.isEmpty()) {
                return c.length() > 300 ? c.substring(0, 300) : c;
            }
        }
        return "";
    }

    /**
     * 取会话中最后一条带时间的消息时间（无则返回 null）
     */
    public static String findLastMessageTime(List<Map<String, Object>> msgs) {
        if (msgs == null) return null;
        String lastTime = null;
        for (Map<String, Object> m : msgs) {
            if (m.get("time") instanceof String t && !t.isEmpty()) {
                lastTime = t;
            }
        }
        return lastTime;
    }

    /**
     * 生成匹配片段：关键字前后各保留约 40 字，压缩空白字符
     * @param content 消息内容
     * @param pos 关键字起始位置
     * @param kwLen 关键字长度
     * @return 片段文本（前后省略号视截断情况添加）
     */
    public static String buildSnippet(String content, int pos, int kwLen) {
        int start = Math.max(0, pos - 40);
        int end = Math.min(content.length(), pos + kwLen + 40);
        String snippet = content.substring(start, end).replaceAll("\\s+", " ").trim();
        return (start > 0 ? "…" : "") + snippet + (end < content.length() ? "…" : "");
    }
}
