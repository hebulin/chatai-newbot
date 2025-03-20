package com.chatai.newbot.controller;

import com.chatai.newbot.model.ChatRequest;
import com.chatai.newbot.service.OfficialDeepSeekV3Service;
import com.chatai.newbot.service.TongYiService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import static com.chatai.newbot.constant.AiConstant.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final OfficialDeepSeekV3Service officialDeepSeekV3Service;
    private final TongYiService tongYiService;

    public ChatController(OfficialDeepSeekV3Service officialDeepSeekV3Service,
                          TongYiService tongYiService) {
        this.officialDeepSeekV3Service = officialDeepSeekV3Service;
        this.tongYiService = tongYiService;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody ChatRequest request) {
        if (request.isDeepThinking()) {
            request.setModel(DEEP_THINK_ALI_SOURCE);
            return tongYiService.chatWithDeepThink(request);
        } else {
            switch (request.getModel()) {
                case DEEPSEEK_V3_ALI_SOURCE:
                    return tongYiService.chat(request);
                case DEEPSEEK_V3_OFFICIAL_SOURCE:
                    return officialDeepSeekV3Service.chat(request);
                case QWEN_MAX_LATEST:
                    return tongYiService.chat(request);
                case QWEN_PLUS_LATEST:
                    return tongYiService.chat(request);
                case QWEN_TURBO_LATEST:
                    return tongYiService.chat(request);
                default:
                    return null;
            }
        }

    }
}