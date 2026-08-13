package com.chatai.newbot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:target/chatai-context-test.db")
class ChataiWithNewbotApplicationTests {

    /** 验证 Spring 容器可使用测试专用 SQLite 数据库完整启动。 */
    @Test
    void contextLoads() {
    }

}
