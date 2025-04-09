package com.chatai.newbot.controller;

import com.chatai.newbot.enums.ModelEnum;
import com.chatai.newbot.model.ChatRequest;
import com.chatai.newbot.service.OfficialDeepSeekV3Service;
import com.chatai.newbot.service.TongYiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import static com.chatai.newbot.constant.AiConstant.*;

@RestController
@RequestMapping("/api")
public class ChatController {
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private final OfficialDeepSeekV3Service officialDeepSeekV3Service;
    private final TongYiService tongYiService;

    public ChatController(OfficialDeepSeekV3Service officialDeepSeekV3Service,
                          TongYiService tongYiService) {
        this.officialDeepSeekV3Service = officialDeepSeekV3Service;
        this.tongYiService = tongYiService;
    }

    @GetMapping("/heartbeat")
    public ResponseEntity<Void> heartbeat() {
        log.info("心跳监测heartbeat");
        return ResponseEntity.ok().build();
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody ChatRequest request) {
        if (request.isDeepThinking()) {
            request.setModel(ModelEnum.getModelValueByName(NAME_DEEP_THINK));
            return tongYiService.chatWithDeepThink(request);
        } else {
            String modelName = request.getModel();
            request.setModel(ModelEnum.getModelValueByName(modelName));
            switch (modelName) {
                case NAME_ALI_DEEPSEEK:
                    return tongYiService.chat(request);
                case NAME_OFFICIAL_DEEPSEEK:
                    return officialDeepSeekV3Service.chat(request);
                case NAME_QWEN_MAX:
                    return tongYiService.chat(request);
                case NAME_QWEN_PLUS:
                    return tongYiService.chat(request);
                case NAME_QWEN_TURBO:
                    return tongYiService.chat(request);
                default:
                    return null;
            }
        }
    }
}