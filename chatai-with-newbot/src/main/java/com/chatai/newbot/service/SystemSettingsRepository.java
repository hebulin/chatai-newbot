package com.chatai.newbot.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** SQLite 系统设置仓储，负责 t_setting 的写穿缓存与持久化。 */
@Repository
public class SystemSettingsRepository {
    private final JdbcTemplate jdbcTemplate;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** 创建系统设置仓储。 */
    public SystemSettingsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 从数据库重新加载全部设置缓存。 */
    public void reload() {
        cache.clear();
        for (Map<String, Object> row : jdbcTemplate.queryForList("SELECT key, value FROM t_setting")) {
            Object value = row.get("value");
            if (value != null) cache.put(String.valueOf(row.get("key")), String.valueOf(value));
        }
    }

    /** 读取设置值；不存在时返回 null。 */
    public String get(String key) {
        return cache.get(key);
    }

    /** 写入设置值，并同步更新内存缓存。 */
    public synchronized void set(String key, String value) {
        int updated = jdbcTemplate.update("UPDATE t_setting SET value = ? WHERE key = ?", value, key);
        if (updated == 0) jdbcTemplate.update("INSERT INTO t_setting (key, value) VALUES (?, ?)", key, value);
        if (value == null) cache.remove(key);
        else cache.put(key, value);
    }
}
