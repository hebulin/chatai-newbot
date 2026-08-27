package com.chatai.newbot.exception;

/**
 * 会话同步版本冲突异常（多端同步乐观锁）。
 * 客户端提交基准版本与服务端当前版本不一致时抛出，
 * 携带服务端当前版本号供调用方组装冲突明细返回前端，
 * 绝不以“最后写入覆盖”方式静默吞掉冲突。
 */
public class ChatSyncConflictException extends RuntimeException {

    /** 冲突的会话ID */
    private final String chatId;
    /** 服务端当前版本号（行不存在时为 0） */
    private final long serverVersion;

    public ChatSyncConflictException(String chatId, long serverVersion) {
        super("会话版本冲突: chatId=" + chatId + ", serverVersion=" + serverVersion);
        this.chatId = chatId;
        this.serverVersion = serverVersion;
    }

    public String getChatId() {
        return chatId;
    }

    public long getServerVersion() {
        return serverVersion;
    }
}
