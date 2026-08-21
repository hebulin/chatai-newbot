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
    /** 用户自定义全局提示词（System Prompt）。旧版单条提示词字段，已被 promptPresets 取代，仅用于历史数据兼容/迁移 */
    private String systemPrompt;
    /** 用户自定义提示词预设列表。可保存多条，最多启用 1 条；被启用的那条作为 system 消息注入，等价于全局提示词 */
    private List<PromptPreset> promptPresets = new ArrayList<>();
    /** 账号是否被禁用：禁用后无法登录、已有登录态立即失效；比删除温和，保留使用记录 */
    private boolean disabled;
    /** 单用户每日限额类型："count"=每日调用次数、"token"=每日Token量，null/空=不单独限制（回退全局配额）。二选一互斥 */
    private String dailyLimitType;
    /** 单用户每日限额数值（与 dailyLimitType 配套使用，<=0 视为不限制） */
    private int dailyLimitValue;
    /** 是否已启用双重验证；属于安全配置，不直接序列化给通用用户接口 */
    @JsonIgnore
    private boolean twoFactorEnabled;
    /** TOTP 共享密钥的 AES-GCM 密文；任何接口都不得返回此字段 */
    @JsonIgnore
    private String twoFactorSecret;
    /** 尚未使用的恢复码 SHA-256 摘要列表；任何接口都不得返回此字段 */
    @JsonIgnore
    private List<String> recoveryCodeHashes = new ArrayList<>();
    /** 最近一次成功使用的 TOTP 时间步，用于阻止同一验证码重放 */
    @JsonIgnore
    private long twoFactorLastUsedStep = -1L;

    @JsonIgnore
    public boolean isAdmin() {
        return "admin".equals(role);
    }
}
