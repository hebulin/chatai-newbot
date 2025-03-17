package com.chatai.newbot.controller;

import com.chatai.newbot.model.ChatRequest;
import com.chatai.newbot.service.AliDeepSeekV3Service;
import com.chatai.newbot.service.OfficialDeepSeekV3Service;
import com.chatai.newbot.service.TongYiService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final OfficialDeepSeekV3Service officialDeepSeekV3Service;
    private final AliDeepSeekV3Service aliDeepSeekV3Service;
    private final TongYiService tongYiService;

    public ChatController(OfficialDeepSeekV3Service officialDeepSeekV3Service, AliDeepSeekV3Service aliDeepSeekV3Service, TongYiService tongYiService) {
        this.officialDeepSeekV3Service = officialDeepSeekV3Service;
        this.aliDeepSeekV3Service = aliDeepSeekV3Service;
        this.tongYiService = tongYiService;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody ChatRequest request) {
        if (request.isDeepThinking()) {
            request.setModel("qwq-32b");
            return tongYiService.chat(request);
        } else {
            switch (request.getModel()) {
                case "ali-deepseek":
                    return aliDeepSeekV3Service.chat(request);
                case "deepseek":
                    return officialDeepSeekV3Service.chat(request);
                default:
                    return tongYiService.chat(request);
            }
        }

    }
}