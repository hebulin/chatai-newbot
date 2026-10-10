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

    /** 仅已登记且属于用户或已被授权给用户的资源可以再次分享；不放行未登记的历史地址。 */
    public boolean canShare(String url, String userId) {
        if (userId == null || userId.isBlank()) return false;
        if (isLegacyPublic(url)) return true;
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_file_asset a WHERE a.url=? "
                        + "AND (a.owner_user_id=? OR EXISTS (SELECT 1 FROM t_file_asset_grant g "
                        + "WHERE g.url=a.url AND g.user_id=?))", Integer.class, normalizeUrl(url), userId, userId);
        return count != null && count > 0;
    }

    /** 在同一 SQL 中验证转授者的资源权限并授予接收者，避免先查后写的越权窗口。 */
    public boolean grantFrom(String url, String sourceUserId, String recipientUserId) {
        if (sourceUserId == null || recipientUserId == null) return false;
        if (isLegacyPublic(url)) return true; // 旧公开资源无须伪造所有者或创建私人授权。
        String normalized = normalizeUrl(url);
        int changed = jdbcTemplate.update("INSERT INTO t_file_asset_grant(url,user_id,created_at) "
                        + "SELECT a.url,?,? FROM t_file_asset a WHERE a.url=? AND (a.owner_user_id=? "
                        + "OR EXISTS (SELECT 1 FROM t_file_asset_grant g WHERE g.url=a.url AND g.user_id=?)) "
                        + "ON CONFLICT(url,user_id) DO UPDATE SET created_at=excluded.created_at",
                recipientUserId, now(), normalized, sourceUserId, sourceUserId);
        return changed > 0;
    }

    /** 判断用户或管理员是否拥有资源访问权。 */
    public boolean canAccess(String url, String userId, boolean admin) {
        String normalizedUrl = normalizeUrl(url);
        List<String> owners = jdbcTemplate.queryForList(
                "SELECT owner_user_id FROM t_file_asset WHERE url=?", String.class, normalizedUrl);
        if (owners.isEmpty()) return isLegacyPublic(url);
        if (admin || (userId != null && userId.equals(owners.get(0)))) return true;
        if (userId == null) return false;
        Integer grants = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_file_asset_grant WHERE url=? AND user_id=?",
                Integer.class, normalizedUrl, userId);
        return grants != null && grants > 0;
    }

    /** 仅迁移时已存在的旧公开资源可走兼容路径；一旦有明确归属，立即服从私有授权。 */
    public boolean isLegacyPublic(String url) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_legacy_public_asset l "
                        + "WHERE l.url=? AND NOT EXISTS (SELECT 1 FROM t_file_asset a WHERE a.url=l.url)",
                Integer.class, normalizeUrl(url));
        return count != null && count > 0;
    }

    /** 首次升级只枚举已有物理文件，不依据用户可编辑的会话数据授予任何私有资源权限。 */
    public void migrateLegacyPublicAssets(java.nio.file.Path uploadRoot) {
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbcTemplate.getDataSource()));
        tx.executeWithoutResult(status -> {
            if (!jdbcTemplate.queryForList("SELECT value FROM t_setting WHERE key='legacy_public_assets_v1'", String.class).isEmpty()) return;
            try {
                if (java.nio.file.Files.isDirectory(uploadRoot)) {
                    try (var files = java.nio.file.Files.walk(uploadRoot)) {
                        for (var file : files.filter(path -> java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)).toList()) {
                            String relative = uploadRoot.relativize(file).toString().replace('\\', '/');
                            String url = relative.matches("[0-9]{6}/[a-zA-Z0-9._-]+") ? "/api/files/img/" + relative
                                    : relative.matches("doc/[0-9]{6}/[a-zA-Z0-9._-]+") ? "/api/files/" + relative : null;
                            if (url == null) continue;
                            jdbcTemplate.update("INSERT OR IGNORE INTO t_legacy_public_asset(url) SELECT ? "
                                    + "WHERE NOT EXISTS (SELECT 1 FROM t_file_asset WHERE url=?)", url, url);
                        }
                    }
                }
                jdbcTemplate.update("INSERT INTO t_setting(key,value) VALUES('legacy_public_assets_v1','true')");
            } catch (java.io.IOException e) {
                throw new IllegalStateException("历史公开资源迁移失败，未放宽未知资源权限", e);
            }
        });
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
