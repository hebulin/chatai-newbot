package com.chatai.newbot.config;

import javax.servlet.http.HttpServletRequest;

/**
 * 客户端 IP 解析工具。
 * 统一 AuthController（登录记录IP）与 AuthInterceptor（请求校验IP）的取值逻辑，
 * 避免因取值方式不一致导致 IP 绑定校验误判。
 */
public final class IpUtils {

    private IpUtils() {}

    public static String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        // 本地回环地址归一化，避免 IPv4(127.0.0.1) / IPv6(::1) 混用导致误判
        if (ip != null && (ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1"))) {
            ip = "127.0.0.1";
        }
        return ip;
    }
}
