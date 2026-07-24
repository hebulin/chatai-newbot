package com.chatai.newbot.service;

import com.chatai.newbot.model.ModelConfig;
import com.chatai.newbot.model.Provider;
import com.chatai.newbot.model.UsageLog;
import com.chatai.newbot.model.User;

import java.util.List;
import java.util.Map;

/**
 * 存储服务接口 - 定义所有数据操作方法签名
 * 由 JsonFileStorageService（JSON文件）和 SqliteStorageService（SQLite数据库）分别实现，
 * 由 StorageManager 根据开关状态委托给对应实现。
 */
public interface StorageService {

    // ========== 用户相关 ==========

    /**
     * 用户认证：根据用户名和密码（明文）验证身份
     * @param username 用户名
     * @param password 明文密码
     * @return 认证成功返回 User 对象，失败返回 null
     */
    User authenticate(String username, String password);

    /**
     * 注册新用户
     * @param username 用户名
     * @param password 明文密码
     * @param ip 注册时IP
     * @return 注册成功返回 User 对象，失败（用户名已存在/不可用）返回 null
     */
    User register(String username, String password, String ip);

    /**
     * 检查指定IP今日是否还能注册（每日每IP限5个）
     * @param ip IP地址
     * @return true=可以注册，false=已达上限
     */
    boolean canRegisterFromIp(String ip);

    /**
     * 更新用户登录信息（登录时间、IP、浏览器）
     * @param userId 用户ID
     * @param ip 登录IP
     * @param browser 浏览器标识
     */
    void updateLoginInfo(String userId, String ip, String browser);

    /**
     * 获取所有用户列表
     * @return 用户列表副本
     */
    List<User> getAllUsers();

    /**
     * 根据ID获取用户
     * @param id 用户ID
     * @return 用户对象，不存在返回 null
     */
    User getUserById(String id);

    /**
     * 删除用户（admin不可删除）
     * @param userId 用户ID
     * @return true=删除成功，false=用户不存在或是admin
     */
    boolean deleteUser(String userId);

    /**
     * 更新用户信息
     * @param user 更新后的用户对象
     */
    void updateUser(User user);

    /**
     * 修改用户密码
     * @param userId 用户ID
     * @param oldPassword 旧密码（明文）
     * @param newPassword 新密码（明文）
     * @return 0=成功; 1=用户不存在; 2=旧密码错误
     */
    int changePassword(String userId, String oldPassword, String newPassword);

    // ========== 模型配置相关 ==========

    /**
     * 获取所有模型配置
     * @return 模型配置列表副本
     */
    List<ModelConfig> getAllModelConfigs();

    /**
     * 获取指定用户可见的模型列表（已启用 + 可见性匹配）
     * @param user 当前用户
     * @return 可见模型列表
     */
    List<ModelConfig> getVisibleModels(User user);

    /**
     * 根据ID获取模型配置
     * @param id 模型配置ID
     * @return 模型配置对象，不存在返回 null
     */
    ModelConfig getModelConfigById(String id);

    /**
     * 添加模型配置（自动填充厂商信息、生成ID）
     * @param config 模型配置
     * @return 保存后的模型配置（含生成的ID）
     */
    ModelConfig addModelConfig(ModelConfig config);

    /**
     * 更新模型配置
     * @param config 更新后的模型配置
     */
    void updateModelConfig(ModelConfig config);

    /**
     * 删除模型配置
     * @param id 模型配置ID
     * @return true=删除成功
     */
    boolean deleteModelConfig(String id);

    // ========== 默认模型 ==========

    /**
     * 获取全局默认模型ID
     * @return 默认模型ID，未设置返回 null
     */
    String getDefaultModelId();

    /**
     * 设置全局默认模型
     * @param modelId 模型配置ID
     */
    void setDefaultModelId(String modelId);

    /**
     * 清除全局默认模型
     */
    void clearDefaultModelId();

    // ========== 厂商相关 ==========

    /**
     * 获取所有厂商（已应用显示名覆盖）
     * @return 厂商列表副本
     */
    List<Provider> getAllProviders();

    /**
     * 通过ID获取原始预置厂商（不应用覆盖）
     * @param providerId 厂商ID
     * @return 厂商对象，不存在返回 null
     */
    Provider getProvider(String providerId);

    /**
     * 获取厂商当前显示名（应用了用户自定义的覆盖）
     * @param providerId 厂商ID
     * @return 显示名，不存在返回 null
     */
    String getProviderDisplayName(String providerId);

    /**
     * 修改厂商显示名/图标
     * @param providerId 预置厂商ID / 自定义厂商固定 "__custom__"
     * @param newName 新的显示名
     * @param newIcon 新的图标（仅自定义厂商生效）
     * @param oldName 自定义厂商的旧名（仅自定义厂商使用）
     * @return 实际被改动的 ModelConfig 数量
     */
    int renameProvider(String providerId, String newName, String newIcon, String oldName);

    /**
     * 获取所有自定义厂商条目（去重，按 providerName 分组）
     * @return 自定义厂商列表
     */
    List<Map<String, Object>> listCustomProviders();

    // ========== 使用记录相关 ==========

    /**
     * 添加使用记录
     * @param log 使用记录
     */
    void addUsageLog(UsageLog log);

    /**
     * 获取所有使用记录
     * @return 使用记录列表
     */
    List<UsageLog> getAllUsageLogs();

    /**
     * 获取指定用户的使用记录
     * @param userId 用户ID
     * @return 该用户的使用记录列表
     */
    List<UsageLog> getUsageLogsByUser(String userId);

    /**
     * 更新使用记录（匹配 userId+timestamp+modelId）
     * @param log 更新后的使用记录
     */
    void updateUsageLog(UsageLog log);

    /**
     * 获取所有已记录的日期列表
     * @return 日期字符串列表（yyyy-MM-dd），已排序
     */
    List<String> getUsageLogDates();
}
