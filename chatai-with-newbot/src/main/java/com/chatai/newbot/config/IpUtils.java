package com.chatai.newbot.config;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端 IP 解析工具。
 * 统一 AuthController（登录记录IP）与 AuthInterceptor（请求校验IP）的取值逻辑，
 * 避免因取值方式不一致导致 IP 绑定校验误判。
 * <p>
 * 安全说明：X-Forwarded-For / X-Real-IP 由客户端直接可控，若无条件采信会被伪造，
 * 进而绕过“Token 绑定登录 IP”“每 IP 注册限额”“基于 IP 的防爆破”等安全机制。
 * 因此仅当请求的直连来源（getRemoteAddr）为可信代理（回环/内网地址）时才采信转发头；
 * 应用被直接暴露到公网时，转发头一律忽略，改用真实直连地址。
 * 若部署拓扑特殊（如代理位于公网地址），可通过系统属性/环境变量
 * {@code chatai.trust-all-proxies=true} 显式放开。
 */
public final class IpUtils {

    private IpUtils() {}

    public static String getClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        // 仅在直连来源可信（或显式全信任）时才采信转发头，否则转发头可被伪造
        if (trustAllProxies() || isTrustedProxy(remoteAddr)) {
            String ip = request.getHeader("X-Forwarded-For");
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = request.getHeader("X-Real-IP");
            }
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                if (ip.contains(",")) {
                    ip = ip.split(",")[0].trim();
                }
                return normalizeLoopback(ip);
            }
        }
        return normalizeLoopback(remoteAddr);
    }

    /** 是否显式配置为信任所有代理（系统属性优先，其次环境变量） */
    public static boolean trustAllProxies() {
        String v = System.getProperty("chatai.trust-all-proxies");
        if (v == null || v.isEmpty()) {
            v = System.getenv("CHATAI_TRUST_ALL_PROXIES");
        }
        return "true".equalsIgnoreCase(v);
    }

    /**
     * 判断直连来源是否为可信代理：回环地址或私有/内网地址。
     * 典型反向代理（nginx 部署在本机或内网）均落在此范围内。
     */
    public static boolean isTrustedProxy(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        // 回环
        if (ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1")
                || ip.startsWith("127.")) {
            return true;
        }
        // IPv4 私有网段：10.0.0.0/8、192.168.0.0/16、172.16.0.0/12
        if (ip.startsWith("10.") || ip.startsWith("192.168.")) {
            return true;
        }
        if (ip.startsWith("172.")) {
            int secondDot = ip.indexOf('.', 4);
            if (secondDot > 4) {
                try {
                    int second = Integer.parseInt(ip.substring(4, secondDot));
                    if (second >= 16 && second <= 31) return true;
                } catch (NumberFormatException ignored) {
                    // 非法格式，按不可信处理
                }
            }
        }
        // IPv6 唯一本地地址(fc00::/7) 与链路本地(fe80::/10)
        String lower = ip.toLowerCase();
        return lower.startsWith("fc") || lower.startsWith("fd") || lower.startsWith("fe80:");
    }

    /** 本地回环地址归一化，避免 IPv4(127.0.0.1) / IPv6(::1) 混用导致误判 */
    private static String normalizeLoopback(String ip) {
        if (ip != null && (ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1"))) {
            return "127.0.0.1";
        }
        return ip;
    }
}
