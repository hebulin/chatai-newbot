package com.chatai.newbot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 计费展示配置服务：人民币作为唯一基础币种，其他币种通过“1 CNY = rate 目标币种”换算。
 */
@Service
public class BillingSettingsService {
    private static final String KEY_DISPLAY_MODE = "billing_display_mode";
    private static final String KEY_DEFAULT_CURRENCY = "billing_default_currency";
    private static final String KEY_CURRENCIES = "billing_currencies";
    private static final Pattern CURRENCY_CODE = Pattern.compile("^[A-Z]{3,8}$");
    private static final Set<String> DISPLAY_MODES = Set.of("token", "currency");

    private final StorageManager storageManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 创建计费配置服务。 */
    public BillingSettingsService(StorageManager storageManager) {
        this.storageManager = storageManager;
    }

    /** 获取可供前端展示的完整计费配置。 */
    public Map<String, Object> getConfig() {
        List<Map<String, Object>> currencies = loadCurrencies();
        String displayMode = storageManager.getSetting(KEY_DISPLAY_MODE);
        if (displayMode == null || !DISPLAY_MODES.contains(displayMode)) displayMode = "token";
        String defaultCurrency = normalizeCode(storageManager.getSetting(KEY_DEFAULT_CURRENCY));
        boolean currencyExists = false;
        for (Map<String, Object> currency : currencies) {
            if (defaultCurrency.equals(currency.get("code"))) {
                currencyExists = true;
                break;
            }
        }
        if (!currencyExists) defaultCurrency = "CNY";
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("displayMode", displayMode);
        config.put("defaultCurrency", defaultCurrency);
        config.put("currencies", currencies);
        return config;
    }

    /** 校验并保存计费展示配置。 */
    public synchronized Map<String, Object> saveConfig(Map<String, Object> body) {
        String displayMode = body == null ? null : String.valueOf(body.get("displayMode"));
        if (!DISPLAY_MODES.contains(displayMode)) throw new IllegalArgumentException("默认显示方式必须是 token 或 currency");
        List<Map<String, Object>> currencies = parseCurrencies(body == null ? null : body.get("currencies"));
        String defaultCurrency = normalizeCode(body == null ? null : String.valueOf(body.get("defaultCurrency")));
        if (currencies.stream().noneMatch(c -> defaultCurrency.equals(c.get("code")))) {
            throw new IllegalArgumentException("默认币种必须存在于币种列表中");
        }
        try {
            storageManager.setSetting(KEY_DISPLAY_MODE, displayMode);
            storageManager.setSetting(KEY_DEFAULT_CURRENCY, defaultCurrency);
            storageManager.setSetting(KEY_CURRENCIES, objectMapper.writeValueAsString(currencies));
            return getConfig();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("保存计费配置失败", e);
        }
    }

    /** 读取币种列表；配置缺失或损坏时回退人民币。 */
    private List<Map<String, Object>> loadCurrencies() {
        String json = storageManager.getSetting(KEY_CURRENCIES);
        if (json != null && !json.isBlank()) {
            try {
                return parseCurrencies(objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {}));
            } catch (Exception ignored) {
                // 配置损坏时回退默认值，保证统计接口可用。
            }
        }
        return new ArrayList<>(List.of(currency("CNY", "人民币", "¥", 1D)));
    }

    /** 校验并规范化币种列表；人民币固定存在且汇率固定为 1。 */
    private List<Map<String, Object>> parseCurrencies(Object raw) {
        if (!(raw instanceof List<?> rows)) throw new IllegalArgumentException("币种列表不能为空");
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        unique.put("CNY", currency("CNY", "人民币", "¥", 1D));
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) continue;
            String code = normalizeCode(stringValue(map.get("code")));
            if ("CNY".equals(code)) continue;
            if (!CURRENCY_CODE.matcher(code).matches()) throw new IllegalArgumentException("币种代码需为 3~8 位大写字母：" + code);
            String name = stringValue(map.get("name")).trim();
            String symbol = stringValue(map.get("symbol")).trim();
            double rate = numberValue(map.get("rate"));
            if (name.isEmpty() || symbol.isEmpty()) throw new IllegalArgumentException("币种名称和符号不能为空：" + code);
            if (!Double.isFinite(rate) || rate <= 0) throw new IllegalArgumentException("币种 " + code + " 必须设置大于 0 的汇率");
            unique.put(code, currency(code, name, symbol, rate));
        }
        return new ArrayList<>(unique.values());
    }

    /** 创建规范化币种配置项。 */
    private Map<String, Object> currency(String code, String name, String symbol, double rate) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", code);
        item.put("name", name);
        item.put("symbol", symbol);
        item.put("rate", rate);
        return item;
    }

    /** 规范化币种代码。 */
    private String normalizeCode(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    /** 安全读取字符串。 */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 安全读取数值。 */
    private double numberValue(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try { return Double.parseDouble(stringValue(value)); } catch (NumberFormatException e) { return 0D; }
    }
}
