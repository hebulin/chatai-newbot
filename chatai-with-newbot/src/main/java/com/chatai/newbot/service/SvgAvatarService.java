package com.chatai.newbot.service;

import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Pattern;

/** 对用户提交的 SVG 头像源码执行白名单外危险能力拦截。 */
@Service
public class SvgAvatarService {
    private static final int MAX_SVG_LENGTH = 20_000;
    private static final Pattern EVENT_HANDLER = Pattern.compile("(?i)\\son[a-z]+\\s*=");
    private static final Pattern EXTERNAL_HREF = Pattern.compile("(?i)(?:href|xlink:href)\\s*=\\s*['\"](?!#)");

    /**
     * 校验并规整 SVG 源码。源码仅允许作为 img 的 data URL 使用，不允许通过 v-html 注入 DOM。
     * @param svg 用户提交的 SVG 源码
     * @return 去除首尾空白后的安全源码
     */
    public String sanitize(String svg) {
        if (svg == null || svg.isBlank()) return "";
        String value = svg.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (value.length() > MAX_SVG_LENGTH) {
            throw new IllegalArgumentException("SVG 头像代码不能超过 20000 个字符");
        }
        if (!lower.startsWith("<svg") || !lower.endsWith("</svg>")) {
            throw new IllegalArgumentException("请输入完整的 SVG 代码");
        }
        String[] denied = {"<script", "<foreignobject", "<iframe", "<object", "<embed",
                "<!doctype", "<!entity", "javascript:", "data:text/html", "url("};
        for (String token : denied) {
            if (lower.contains(token)) throw new IllegalArgumentException("SVG 中包含不允许的内容");
        }
        if (EVENT_HANDLER.matcher(value).find() || EXTERNAL_HREF.matcher(value).find()) {
            throw new IllegalArgumentException("SVG 中包含不允许的事件或外部资源引用");
        }
        return value;
    }
}
