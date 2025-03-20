package com.chatai.newbot.service;

import com.chatai.newbot.model.ChatRequest;
import reactor.core.publisher.Flux;

public interface ChatService {
    Flux<String> chat(ChatRequest request);
}
