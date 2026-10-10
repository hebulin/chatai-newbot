package com.chatai.newbot.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 同一文件系统内暂存并切换恢复资源；数据库提交前保留原始文件以便补偿回滚。 */
class RestoreFileSwap implements AutoCloseable {
    private final Path staging;
    private final Path dataDir;
    private final List<String> originals = new ArrayList<>();
    private final List<String> installed = new ArrayList<>();
    private final boolean replaceSecret;
    private boolean preserve;

    /** 先复制全部新资源到同盘暂存目录，复制失败不会触碰现有数据。 */
    RestoreFileSwap(Path source, Path dataDir) throws IOException {
        this.dataDir = dataDir;
        staging = Files.createTempDirectory(dataDir, ".restore-stage-");
        replaceSecret = Files.exists(source.resolve("apikey.secret"));
        try {
            Path newUploads = staging.resolve("new-uploads");
            Files.createDirectories(newUploads);
            if (Files.exists(source.resolve("uploads"))) {
                try (var paths = Files.walk(source.resolve("uploads"))) {
                    for (Path file : paths.filter(Files::isRegularFile).toList()) {
                        Path target = newUploads.resolve(source.resolve("uploads").relativize(file));
                        Files.createDirectories(target.getParent());
                        Files.copy(file, target);
                    }
                }
            }
            if (replaceSecret) Files.copy(source.resolve("apikey.secret"), staging.resolve("new-apikey.secret"));
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    /** 移走旧文件后安装新文件；原始目录始终保留至整个数据库事务成功提交。 */
    void apply() throws IOException {
        swap("uploads");
        if (replaceSecret) swap("apikey.secret");
        ApiKeyCrypto.reloadKey();
    }

    /** 仅在同一文件系统执行原子重命名，不能原子切换的平台明确失败并回滚。 */
    protected void move(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    /** 切换一个已完成暂存的资源，并分别记录原文件与新文件的移动状态。 */
    private void swap(String name) throws IOException {
        Path target = dataDir.resolve(name);
        if (Files.exists(target)) {
            move(target, staging.resolve("old-" + name));
            originals.add(name);
        }
        move(staging.resolve("new-" + name), target);
        installed.add(name);
    }

    /** 撤销文件切换并重载旧密钥；补偿失败时保留暂存目录供运维修复。 */
    void rollback() throws IOException {
        try {
            for (int i = installed.size() - 1; i >= 0; i--) {
                String name = installed.get(i);
                move(dataDir.resolve(name), staging.resolve("failed-" + name));
            }
            for (int i = originals.size() - 1; i >= 0; i--) {
                String name = originals.get(i);
                move(staging.resolve("old-" + name), dataDir.resolve(name));
            }
            ApiKeyCrypto.reloadKey();
        } catch (IOException | RuntimeException e) {
            preserve = true;
            throw new IOException("资源回滚失败，原始文件保留于 " + staging, e);
        }
    }

    /** 清理已提交或已安全回滚的暂存文件，清理失败不改变恢复的提交结果。 */
    @Override
    public void close() {
        if (preserve) return;
        try (var paths = Files.walk(staging)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) { /* 仅残留暂存副本，不删除现有运行文件 */ }
    }
}
