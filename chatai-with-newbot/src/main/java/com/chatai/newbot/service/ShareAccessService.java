package com.chatai.newbot.service;

import com.chatai.newbot.model.ChatShare;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 分享资源校验与短期访问凭证；凭证仅在成功消费一次访问额度后签发。 */
@Service
public class ShareAccessService {
    private static final long TOKEN_TTL_SECONDS = 15 * 60;
    private final FileAssetRepository assets;
    private final ChatShareRepository shares;
    private final byte[] signingKey = new byte[32];

    /** 使用进程随机密钥，重启后旧的短期分享凭证自动失效。 */
    public ShareAccessService(FileAssetRepository assets, ChatShareRepository shares) {
        this.assets = assets;
        this.shares = shares;
        new SecureRandom().nextBytes(signingKey);
    }

    /** 在最新安全策略仍与已验证记录一致时原子消费访问额度。 */
    public boolean claimAccess(ChatShare share) {
        return !isExpired(share) && shares.claimAccess(share);
    }

    /** 检查分享是否过期；无效的非空日期按失效处理。 */
    public boolean isExpired(ChatShare share) {
        if (share == null) return true;
        if (share.getExpiresAt() == null || share.getExpiresAt().isBlank()) return false;
        try {
            return !LocalDateTime.now().isBefore(LocalDateTime.parse(share.getExpiresAt(),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** 校验快照所有层级的本地附件归属，历史版本及旧分享也必须通过相同检查。 */
    public void validateAssets(Object messages, String ownerUserId) {
        for (String url : resourceUrls(messages)) {
            if (!assets.canShare(url, ownerUserId)) {
                throw new IllegalArgumentException("分享包含无权访问或未登记的附件，请移除或重新上传");
            }
        }
    }

    /** 校验已登记资源的真实用户访问权，不以难猜的文件名作为授权依据。 */
    public boolean canAccessAsset(String url, String userId) {
        return assets.canShare(url, userId);
    }

    /** 保持迁移前已公开资源的读取兼容；新资源一律校验用户或短期分享凭证。 */
    public boolean isLegacyPublic(String url) {
        return assets.isLegacyPublic(url);
    }

    /** 复制分享时再次以原分享者身份校验并原子转授资源，不能凭客户端 URL 任意授权。 */
    public void grantAssets(Object messages, String ownerUserId, String recipientUserId) {
        validateAssets(messages, ownerUserId);
        for (String url : resourceUrls(messages)) {
            if (!assets.grantFrom(url, ownerUserId, recipientUserId)) {
                throw new IllegalArgumentException("分享附件权限已变更，请刷新后重试");
            }
        }
    }

    /** 收集图片、文档和历史版本中的本地资源；查询串不参与持久授权。 */
    public Set<String> resourceUrls(Object value) {
        Set<String> urls = new LinkedHashSet<>();
        collectResources(value, urls);
        return urls;
    }

    /** 仅识别资源字段；正文中的接口路径属于文本，不能被误判为附件。 */
    private void collectResources(Object value, Set<String> urls) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("images") instanceof List<?> images) {
                images.forEach(image -> collectUrl(image, urls));
            }
            if (map.get("attachments") instanceof List<?> attachments) {
                for (Object attachment : attachments) {
                    if (attachment instanceof Map<?, ?> item) collectUrl(item.get("url"), urls);
                }
            }
            map.values().forEach(child -> collectResources(child, urls));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> collectResources(child, urls));
        }
    }

    /** 校验并规整一个实际资源字段，外部图片和内嵌图片不产生本地授权。 */
    private void collectUrl(Object value, Set<String> urls) {
        if (!(value instanceof String text) || !text.startsWith("/api/files/")) return;
        String url = text.split("\\?", 2)[0];
        if (!url.matches("/api/files/(img|doc)/[0-9]{6}/[a-zA-Z0-9._-]+")) {
            throw new IllegalArgumentException("分享包含无效的附件地址");
        }
        urls.add(url);
    }

    /** 为一次已获准的阅读签发短期凭证，最后一次额度的图片仍可正常加载。 */
    public String issueToken(ChatShare share) {
        long expires = Instant.now().getEpochSecond() + TOKEN_TTL_SECONDS;
        return expires + "." + signature(share, expires);
    }

    /** 每次验证使用最新分享策略：撤销、到期、改密、修改快照或次数上限均使旧凭证失效。 */
    public boolean verifyToken(ChatShare share, String token) {
        if (isExpired(share) || token == null) return false;
        try {
            String[] parts = token.split("\\.", 2);
            long expires = Long.parseLong(parts[0]);
            long now = Instant.now().getEpochSecond();
            if (parts.length != 2 || expires <= now || expires > now + TOKEN_TTL_SECONDS) return false;
            return MessageDigest.isEqual(signature(share, expires).getBytes(StandardCharsets.US_ASCII),
                    parts[1].getBytes(StandardCharsets.US_ASCII));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 将权限相关字段纳入 MAC，访问计数不参与以支持同一次阅读的多张图片。 */
    private String signature(ChatShare share, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            for (Object field : new Object[]{share.getId(), share.getUserId(), share.getPasswordHash(),
                    share.getExpiresAt(), share.getMaxViews(), share.getSnapshotJson(), share.isSanitized(), expires}) {
                byte[] bytes = String.valueOf(field).getBytes(StandardCharsets.UTF_8);
                mac.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                mac.update(bytes);
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal());
        } catch (Exception e) {
            throw new IllegalStateException("生成分享访问凭证失败", e);
        }
    }
}
