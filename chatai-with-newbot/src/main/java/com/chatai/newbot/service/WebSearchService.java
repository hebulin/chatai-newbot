package com.chatai.newbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 联网搜索服务（Tavily）。
 * 在用户开启"联网"开关时，用最后一条用户消息作为检索词调用 Tavily Search API，
 * 将检索结果整理为一段"参考资料"文本注入到模型请求中（RAG 方式），
 * 使模型能够基于实时网络信息作答。
 * <p>
 * 配置项存于 t_setting（两种存储模式通用）：
 * - web_search_enabled：是否全局启用联网能力
 * - tavily_api_key：Tavily API Key（后台配置，前端仅显示掩码）
 */
@Service
public class WebSearchService {
    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);
    private static final String TAVILY_URL = "https://api.tavily.com/search";
    /** 注入模型上下文的检索结果条数上限 */
    private static final int MAX_RESULTS = 5;
    /** 单条网页正文截断长度，避免上下文过长 */
    private static final int SNIPPET_MAX = 800;

    private final StorageManager storageService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    // Tavily 端点与鉴权在请求时动态设置，此处复用同一个 WebClient 实例
    private final WebClient webClient = WebClient.builder()
            .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
            .build();

    public WebSearchService(StorageManager storageService) {
        this.storageService = storageService;
    }

    /** 是否已启用联网搜索（全局开关开启且已配置 API Key） */
    public boolean isEnabled() {
        String enabled = storageService.getSetting("web_search_enabled");
        String key = storageService.getSetting("tavily_api_key");
        return "true".equals(enabled) && key != null && !key.trim().isEmpty();
    }

    /**
     * 用给定检索词调用 Tavily，并将结果整理为可直接注入模型的"参考资料"文本。
     * 任何异常均返回 null（联网失败不阻断正常对话）。
     * @param query 检索词（通常为用户最后一条消息）
     * @return 参考资料文本；未启用/检索失败/无结果返回 null
     */
    public String searchAsContext(String query) {
        if (query == null || query.trim().isEmpty() || !isEnabled()) {
            return null;
        }
        String apiKey = storageService.getSetting("tavily_api_key");
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("query", query.trim());
            body.put("search_depth", "advanced");
            body.put("max_results", MAX_RESULTS);
            body.put("include_answer", true);

            String resp = webClient.post()
                    .uri(TAVILY_URL)
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(15));
            return formatContext(query.trim(), resp);
        } catch (Exception e) {
            log.warn("联网搜索失败（不阻断对话）: {}", e.getMessage());
            return null;
        }
    }

    /** 将 Tavily 应答整理为参考资料文本（含当前日期，便于时效类问题作答） */
    @SuppressWarnings("unchecked")
    private String formatContext(String query, String resp) {
        if (resp == null || resp.isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(resp, Map.class);
            List<Map<String, Object>> results = (List<Map<String, Object>>) parsed.get("results");
            Object answer = parsed.get("answer");

            StringBuilder sb = new StringBuilder();
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy 年 MM 月 dd 日"));
            sb.append("【联网搜索参考资料】（检索词：").append(query)
                    .append("；当前日期：").append(today).append("）\n");
            sb.append("请优先依据以下实时网络信息作答，并在合适处标注信息来源链接；若资料与问题无关请忽略。\n\n");

            if (answer instanceof String s && !s.trim().isEmpty()) {
                sb.append("摘要：").append(s.trim()).append("\n\n");
            }

            List<Map<String, Object>> sources = new ArrayList<>();
            if (results != null) {
                int idx = 1;
                for (Map<String, Object> r : results) {
                    if (idx > MAX_RESULTS) break;
                    String title = str(r.get("title"));
                    String url = str(r.get("url"));
                    String content = str(r.get("content"));
                    if (content.length() > SNIPPET_MAX) {
                        content = content.substring(0, SNIPPET_MAX) + "…";
                    }
                    sb.append("[").append(idx).append("] ").append(title).append("\n");
                    sb.append("来源：").append(url).append("\n");
                    sb.append("内容：").append(content).append("\n\n");
                    Map<String, Object> src = new HashMap<>();
                    src.put("index", idx);
                    src.put("title", title);
                    src.put("url", url);
                    sources.add(src);
                    idx++;
                }
            }
            if (sources.isEmpty() && !(answer instanceof String)) {
                return null;
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("解析 Tavily 应答失败: {}", e.getMessage());
            return null;
        }
    }

    /** 连通性测试：用一个固定检索词验证 API Key 是否可用 */
    public Map<String, Object> testConnection(String apiKey) {
        Map<String, Object> result = new HashMap<>();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "请先填写 Tavily API Key");
            return result;
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("query", "hello");
            body.put("max_results", 1);
            webClient.post()
                    .uri(TAVILY_URL)
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(15));
            result.put("success", true);
            result.put("message", "Tavily 连接成功");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "连接失败：" + e.getMessage());
        }
        return result;
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
