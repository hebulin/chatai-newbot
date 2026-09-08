package com.chatai.newbot.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** SQLite 上传资源所有权与授权仓储。 */
@Repository
public class FileAssetRepository {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final JdbcTemplate jdbcTemplate;

    /** 创建资源授权仓储。 */
    public FileAssetRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 登记新上传资源的所有者。 */
    public void register(String url, String ownerUserId, String assetType) {
        jdbcTemplate.update("INSERT INTO t_file_asset(url,owner_user_id,asset_type,created_at) VALUES(?,?,?,?) " +
                        "ON CONFLICT(url) DO UPDATE SET owner_user_id=excluded.owner_user_id, asset_type=excluded.asset_type",
                normalizeUrl(url), ownerUserId, assetType, now());
    }

    /** 为用户授予已登记资源的访问权。 */
    public void grant(String url, String userId) {
        String normalizedUrl = normalizeUrl(url);
        if (normalizedUrl.isEmpty() || userId == null || userId.isBlank()) return;
        Integer tracked = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_file_asset WHERE url=?", Integer.class, normalizedUrl);
        if (tracked == null || tracked == 0) return;
        jdbcTemplate.update("INSERT OR IGNORE INTO t_file_asset_grant(url,user_id,created_at) VALUES(?,?,?)",
                normalizedUrl, userId, now());
    }

    /** 判断用户或管理员是否拥有资源访问权。 */
    public boolean canAccess(String url, String userId, boolean admin) {
        String normalizedUrl = normalizeUrl(url);
        List<String> owners = jdbcTemplate.queryForList(
                "SELECT owner_user_id FROM t_file_asset WHERE url=?", String.class, normalizedUrl);
        if (owners.isEmpty() || admin || (userId != null && userId.equals(owners.get(0)))) return true;
        if (userId == null) return false;
        Integer grants = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_file_asset_grant WHERE url=? AND user_id=?",
                Integer.class, normalizedUrl, userId);
        return grants != null && grants > 0;
    }

    /** 去掉资源 URL 查询串，避免临时参数影响所有权判定。 */
    private String normalizeUrl(String url) {
        if (url == null) return "";
        int queryIndex = url.indexOf('?');
        return queryIndex >= 0 ? url.substring(0, queryIndex) : url;
    }

    /** 生成数据库时间字符串。 */
    private String now() {
        return LocalDateTime.now().format(TIME_FORMAT);
    }
}
