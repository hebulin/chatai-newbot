package com.chatai.newbot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@SpringBootApplication
@EnableScheduling
@Slf4j
public class ChataiWithNewbotApplication {

    public static void main(String[] args) {
        ensureDataDir();
        SpringApplication.run(ChataiWithNewbotApplication.class, args);
        log.info("启动成功");
    }

    /**
     * 确保 data 目录存在（空白部署冷启动兼容）。
     * sqlite-jdbc 会自动创建 chatai.db 文件但不会创建父目录，
     * 必须在容器启动（Hikari 首次建连）前建好，否则报 SQLITE_CANTOPEN。
     */
    private static void ensureDataDir() {
        Path dataDir = Paths.get(System.getProperty("user.dir"), "data");
        try {
            Files.createDirectories(dataDir);
        } catch (Exception e) {
            // 不中断启动：目录确实不可用时后续数据源初始化会抛出更明确的错误
            System.err.println("创建 data 目录失败: " + dataDir.toAbsolutePath() + " - " + e.getMessage());
        }
    }

}
