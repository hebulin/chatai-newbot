package com.chatai.newbot.service;

import com.chatai.newbot.model.ChatShare;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 SQLite 验证资源转授 SQL 与分享访问的比较并交换条件。 */
class ShareAccessRepositoryTest {
    @TempDir Path dir;

    /** 仅资源所有者或已有被授权者可转授，未登记地址默认拒绝。 */
    @Test
    void resourceGrantRequiresSourcePermission() {
        JdbcTemplate jdbc = database();
        FileAssetRepository assets = new FileAssetRepository(jdbc);
        String url = "/api/files/doc/202610/file.txt";
        assets.register(url, "owner", "document");
        assertFalse(assets.grantFrom(url, "attacker", "attacker"));
        assertFalse(assets.canShare(url, "attacker"));
        assertTrue(assets.grantFrom(url, "owner", "reader"));
        assertTrue(assets.grantFrom(url + "?shareId=x", "reader", "next"));
        assertTrue(assets.canShare(url, "next"));
        assertFalse(assets.canShare("/api/files/img/202610/legacy.png", "owner"));
    }

    /** 已通过密码校验的旧记录不能在并发改密后消费额度，最后一次额度只能消费一次。 */
    @Test
    void shareClaimRejectsChangedPolicyAndExhaustedQuota() {
        ChatShareRepository shares = new ChatShareRepository(database());
        ChatShare share = new ChatShare();
        share.setId("s"); share.setUserId("owner"); share.setChatId("c");
        share.setMaxViews(1); share.setPasswordHash("old"); share.setSnapshotJson("[]");
        shares.insert(share);
        shares.updateSecurity("s", "new", null, 1, false);
        assertFalse(shares.claimAccess(share));
        share = shares.findById("s");
        assertTrue(shares.claimAccess(share));
        assertFalse(shares.claimAccess(share));
    }

    /** 迁移只接纳当时磁盘上原先公开的资源，不抢占私有归属，不放行日后未登记的文件。 */
    @Test
    void legacyMigrationIsOnceOnlyAndNeverOverridesPrivateAssets() throws Exception {
        JdbcTemplate jdbc = database();
        FileAssetRepository assets = new FileAssetRepository(jdbc);
        Path uploads = dir.resolve("uploads");
        java.nio.file.Files.createDirectories(uploads.resolve("202610"));
        java.nio.file.Files.writeString(uploads.resolve("202610/old.png"), "old");
        java.nio.file.Files.writeString(uploads.resolve("202610/private.png"), "private");
        String legacy = "/api/files/img/202610/old.png";
        String owned = "/api/files/img/202610/private.png";
        assets.register(owned, "owner", "image");
        assets.migrateLegacyPublicAssets(uploads);
        assertTrue(assets.isLegacyPublic(legacy));
        assertTrue(assets.canAccess(legacy, null, false));
        assertTrue(assets.canShare(legacy, "reader"));
        assertFalse(assets.isLegacyPublic(owned));
        assertFalse(assets.canShare(owned, "reader"));
        java.nio.file.Files.writeString(uploads.resolve("202610/later.png"), "later");
        assets.migrateLegacyPublicAssets(uploads);
        assertFalse(assets.canAccess("/api/files/img/202610/later.png", "reader", false));
        assets.register(legacy, "owner", "image");
        assertFalse(assets.isLegacyPublic(legacy));
        assertFalse(assets.canShare(legacy, "reader"));
    }

    /** 在独立临时数据库创建真实表结构，不使用运行数据目录。 */
    private JdbcTemplate database() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("test.db"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        new SqliteSchemaInitializer(jdbc).createTables();
        return jdbc;
    }
}
