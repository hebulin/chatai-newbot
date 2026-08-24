package com.chatai.newbot.service;

import com.chatai.newbot.model.ProviderModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 从兼容 OpenAI 的上游 /models 接口读取模型目录。 */
@Service
public class UpstreamModelCatalogService {
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 获取上游最新模型列表，兼容 {data:[...]}/{models:[...]}/数组三种常见响应。
     * @param apiUrl 厂商 API 基础地址
     * @param apiKey 可选 API Key
     * @return 按模型 ID 排序并去重的模型列表
     */
    public List<ProviderModel> fetch(String apiUrl, String apiKey) {
        String endpoint = normalizeModelsEndpoint(apiUrl);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .GET();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey.trim());
        }
        try {
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("上游返回 HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode rows = root.isArray() ? root : root.path("data");
            if (!rows.isArray()) rows = root.path("models");
            if (!rows.isArray()) throw new IllegalStateException("上游响应中未找到模型列表");
            Map<String, ProviderModel> unique = new LinkedHashMap<>();
            for (JsonNode row : rows) {
                String id = row.isTextual() ? row.asText() : row.path("id").asText("");
                if (id.isBlank()) id = row.path("name").asText("");
                if (id.isBlank()) continue;
                ProviderModel model = new ProviderModel();
                model.setId(id);
                model.setName(row.path("display_name").asText(
                        row.path("displayName").asText(row.path("name").asText(id))));
                model.setSupportsThinking(row.path("supportsThinking").asBoolean(false));
                model.setSupportsMultimodal(row.path("supportsMultimodal").asBoolean(false));
                unique.putIfAbsent(id, model);
            }
            List<ProviderModel> models = new ArrayList<>(unique.values());
            models.sort(Comparator.comparing(ProviderModel::getId, String.CASE_INSENSITIVE_ORDER));
            return models;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("获取上游模型列表已中断", e);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("API 地址格式无效", e);
        } catch (Exception e) {
            throw new IllegalStateException("获取上游模型列表失败：" + e.getMessage(), e);
        }
    }

    /**
     * 将 API 基础地址转换为模型目录地址：以 /v1 结尾时追加 /models，否则追加 /v1/models。
     */
    public String normalizeModelsEndpoint(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) throw new IllegalArgumentException("API 地址不能为空");
        String value = apiUrl.trim().replaceAll("/+$", "");
        if (value.endsWith("/models")) return value;
        if (value.matches(".*/v\\d+$")) return value + "/models";
        return value + "/v1/models";
    }
}
