package com.chatai.newbot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@Slf4j
public class ChataiWithNewbotApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChataiWithNewbotApplication.class, args);
        log.info("启动成功");
    }

}
