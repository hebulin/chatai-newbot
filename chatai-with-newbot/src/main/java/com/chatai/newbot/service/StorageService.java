package com.chatai.newbot.service;

import com.chatai.newbot.model.ModelConfig;
import com.chatai.newbot.model.Provider;
import com.chatai.newbot.model.UsageLog;
import com.chatai.newbot.model.User;

import java.util.List;
import java.util.Map;

/**
 * 存储服务接口 - 定义所有数据操作方法签名
 * 由 SqliteStorageService（SQLite数据库）实现，由 StorageManager 统一委托对外暴露。
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
     * 按用户名模糊匹配分页查询用户（按创建时间升序）
     * @param keyword 用户名关键字，null/空=不筛选
     * @param offset 偏移量（从0开始）
     * @param limit 返回条数上限
     * @return 当页用户列表
     */
    List<User> queryUsers(String keyword, int offset, int limit);

    /**
     * 按用户名模糊匹配统计用户总数（与 queryUsers 相同筛选口径）
     * @param keyword 用户名关键字，null/空=不筛选
     * @return 用户总数
     */
    int countUsers(String keyword);

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
     * 统计指定用户在指定日期的调用次数（用于每日配额限流）
     * @param userId 用户ID
     * @param day 日期字符串（yyyy-MM-dd）
     * @return 当日调用次数
     */
    int countUsageByUserAndDay(String userId, String day);

    /**
     * 统计指定用户在指定日期消耗的 Token 总量（prompt + completion，用于每日 Token 限额）
     * @param userId 用户ID
     * @param day 日期字符串（yyyy-MM-dd）
     * @return 当日 Token 总量
     */
    long sumTokensByUserAndDay(String userId, String day);

    /**
     * 统计指定用户在指定日期的人民币成本总额。
     * @param userId 用户ID
     * @param day 日期字符串（yyyy-MM-dd）
     * @return 当日人民币元成本
     */
    double sumCostCnyByUserAndDay(String userId, String day);

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

    // ========== 使用记录查询下推（筛选/分页/聚合在存储层完成，避免全表加载到内存） ==========

    /**
     * 按条件分页查询使用记录（按时间降序）
     * @param username 用户名筛选，null/空=不筛选
     * @param modelName 模型名筛选，null/空=不筛选
     * @param startDate 开始日期（yyyy-MM-dd，含当天），null/空=不限
     * @param endDate 结束日期（yyyy-MM-dd，含当天），null/空=不限
     * @param offset 偏移量（从0开始）
     * @param limit 返回条数上限
     * @return 当页使用记录列表
     */
    List<UsageLog> queryUsageLogs(String username, String modelName, String startDate, String endDate, int offset, int limit);

    /**
     * 按条件统计使用记录总条数（与 queryUsageLogs 相同的筛选口径）
     * @param username 用户名筛选，null/空=不筛选
     * @param modelName 模型名筛选，null/空=不筛选
     * @param startDate 开始日期（含当天），null/空=不限
     * @param endDate 结束日期（含当天），null/空=不限
     * @return 记录总条数
     */
    int countUsageLogs(String username, String modelName, String startDate, String endDate);

    /**
     * 按条件汇总使用量（总调用次数与各类 Token 总量）
     * @param username 用户名筛选，null/空=不筛选
     * @param startDate 开始日期（含当天），null/空=不限
     * @param endDate 结束日期（含当天），null/空=不限
     * @return 含 calls/promptTokens/completionTokens/reasoningTokens 的汇总 Map
     */
    Map<String, Long> summarizeUsage(String username, String startDate, String endDate);

    /**
     * 按（用户名, 日期, 模型名）维度聚合使用统计
     * @param usernames 用户名列表筛选，null/空=不筛选
     * @param modelName 模型名筛选，null/空=不筛选
     * @param startDate 开始日期（含当天），null/空=不限
     * @param endDate 结束日期（含当天），null/空=不限
     * @return 聚合行列表，每行含 username/date/modelName/count/promptTokens/completionTokens/
     *         cachedTokens/reasoningTokens/thinkingCount，按日期降序、用户名/模型名升序
     */
    List<Map<String, Object>> aggregateUsageStats(List<String> usernames, String modelName, String startDate, String endDate);

    /**
     * 按（用户名, 日期, 模型名）维度聚合使用统计（SQL 分页版）
     * @param usernames 用户名列表筛选，null/空=不筛选
     * @param modelName 模型名筛选，null/空=不筛选
     * @param startDate 开始日期（含当天），null/空=不限
     * @param endDate 结束日期（含当天），null/空=不限
     * @param offset 偏移量（从0开始）
     * @param limit 返回条数上限
     * @return 当页聚合行列表（字段同全量版）
     */
    List<Map<String, Object>> aggregateUsageStats(List<String> usernames, String modelName, String startDate, String endDate, int offset, int limit);

    /**
     * 统计聚合分组总数（与 aggregateUsageStats 相同筛选口径，分页总数用）
     * @param usernames 用户名列表筛选，null/空=不筛选
     * @param modelName 模型名筛选，null/空=不筛选
     * @param startDate 开始日期（含当天），null/空=不限
     * @param endDate 结束日期（含当天），null/空=不限
     * @return 分组总数
     */
    int countUsageStatGroups(List<String> usernames, String modelName, String startDate, String endDate);

    /**
     * 获取使用记录中出现过的用户名列表（去重排序，筛选下拉框用）
     * @return 用户名列表
     */
    List<String> getUsageUsernames();

    /**
     * 获取使用记录中出现过的模型名列表（去重排序，筛选下拉框用）
     * @param username 仅统计该用户使用过的模型，null/空=全部用户
     * @return 模型名列表
     */
    List<String> getUsageModelNames(String username);
}
