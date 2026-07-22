package com.chatai.newbot.controller;

import com.chatai.newbot.model.UsageLog;
import com.chatai.newbot.model.User;
import com.chatai.newbot.service.StorageManager;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 个人/全局使用统计接口（index 页面"数据统计"弹窗专用）。
 * 权限规则：
 *  - 管理员：可查询所有用户数据，支持 username 筛选；
 *  - 普通用户：强制只返回自己的数据，忽略传入的 username 参数。
 * 响应结构与 AdminController 的 /api/admin/usage* 保持一致，便于前端复用渲染逻辑。
 */
@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final StorageManager storageService;

    public UsageController(StorageManager storageService) {
        this.storageService = storageService;
    }

    /**
     * 解析当前请求允许查询的用户名。
     * 管理员返回传入的 username（可为 null 表示全部）；普通用户强制返回自己的用户名。
     */
    private String resolveUsernameScope(HttpServletRequest request, String requestedUsername) {
        User user = (User) request.getAttribute("currentUser");
        if (user != null && user.isAdmin()) {
            return (requestedUsername != null && !requestedUsername.isEmpty()) ? requestedUsername : null;
        }
        return user != null ? user.getUsername() : "";
    }

    private boolean isAdmin(HttpServletRequest request) {
        User user = (User) request.getAttribute("currentUser");
        return user != null && user.isAdmin();
    }

    /**
     * 使用记录查询 - 分页 + 筛选（普通用户仅限本人）
     */
    @GetMapping("")
    public Map<String, Object> getUsageLogs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String modelName,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> rangeCheck = validateDateRange(startDate, endDate);
        if (rangeCheck != null) return rangeCheck;

        String effectiveUsername = resolveUsernameScope(request, username);
        List<UsageLog> logs = filterLogs(storageService.getAllUsageLogs(), effectiveUsername, modelName, startDate, endDate);

        logs.sort((a, b) -> {
            if (a.getTimestamp() == null && b.getTimestamp() == null) return 0;
            if (a.getTimestamp() == null) return 1;
            if (b.getTimestamp() == null) return -1;
            return b.getTimestamp().compareTo(a.getTimestamp());
        });

        int total = logs.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) total / size));
        int fromIndex = Math.min((page - 1) * size, total);
        int toIndex = Math.min(fromIndex + size, total);

        result.put("success", true);
        result.put("data", logs.subList(fromIndex, toIndex));
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("totalPages", totalPages);
        result.put("isAdmin", isAdmin(request));
        return result;
    }

    /**
     * 筛选选项 - 管理员返回全部用户名/模型名；普通用户仅返回本人及其使用过的模型
     */
    @GetMapping("/filters")
    public Map<String, Object> getUsageFilters(HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        User user = (User) request.getAttribute("currentUser");
        List<UsageLog> logs = storageService.getAllUsageLogs();

        List<String> usernames;
        List<String> modelNames;
        if (user != null && user.isAdmin()) {
            usernames = logs.stream().map(UsageLog::getUsername).filter(Objects::nonNull).distinct().sorted().collect(Collectors.toList());
            modelNames = logs.stream().map(UsageLog::getModelName).filter(Objects::nonNull).distinct().sorted().collect(Collectors.toList());
        } else {
            String me = user != null ? user.getUsername() : "";
            usernames = Collections.singletonList(me);
            modelNames = logs.stream()
                    .filter(l -> me.equals(l.getUsername()))
                    .map(UsageLog::getModelName)
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
        }

        result.put("success", true);
        result.put("usernames", usernames);
        result.put("modelNames", modelNames);
        result.put("isAdmin", user != null && user.isAdmin());
        return result;
    }

    /**
     * 使用统计 - 按用户+日期+模型聚合（普通用户仅限本人）
     * getAll=true 时不分页，返回全量聚合数据（图表专用）
     */
    @GetMapping("/stats")
    public Map<String, Object> getUsageStats(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String modelName,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "false") boolean getAll,
            HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> rangeCheck = validateDateRange(startDate, endDate);
        if (rangeCheck != null) return rangeCheck;

        String effectiveUsername = resolveUsernameScope(request, username);
        List<UsageLog> logs = filterLogs(storageService.getAllUsageLogs(), effectiveUsername, modelName, startDate, endDate);

        Map<String, List<UsageLog>> grouped = logs.stream()
                .collect(Collectors.groupingBy(l ->
                        (l.getUsername() != null ? l.getUsername() : "未知") + "|" +
                        (l.getTimestamp() != null ? l.getTimestamp().substring(0, Math.min(10, l.getTimestamp().length())) : "未知") + "|" +
                        (l.getModelName() != null ? l.getModelName() : "未知")
                ));

        List<Map<String, Object>> statsList = new ArrayList<>();
        for (Map.Entry<String, List<UsageLog>> entry : grouped.entrySet()) {
            String[] keys = entry.getKey().split("\\|", 3);
            List<UsageLog> group = entry.getValue();
            Map<String, Object> row = new HashMap<>();
            row.put("username", keys[0]);
            row.put("date", keys[1]);
            row.put("modelName", keys[2]);
            row.put("count", group.size());
            row.put("promptTokens", group.stream().mapToInt(UsageLog::getPromptTokens).sum());
            row.put("completionTokens", group.stream().mapToInt(UsageLog::getCompletionTokens).sum());
            row.put("cachedTokens", group.stream().mapToInt(UsageLog::getCachedTokens).sum());
            row.put("reasoningTokens", group.stream().mapToInt(UsageLog::getReasoningTokens).sum());
            row.put("thinkingCount", group.stream().filter(UsageLog::isDeepThinking).count());
            statsList.add(row);
        }

        statsList.sort((a, b) -> {
            int dateCompare = ((String) b.get("date")).compareTo((String) a.get("date"));
            if (dateCompare != 0) return dateCompare;
            int userCompare = ((String) a.get("username")).compareTo((String) b.get("username"));
            if (userCompare != 0) return userCompare;
            return ((String) a.get("modelName")).compareTo((String) b.get("modelName"));
        });

        int total = statsList.size();
        result.put("success", true);
        result.put("total", total);
        result.put("isAdmin", isAdmin(request));

        if (getAll) {
            result.put("data", statsList);
            result.put("page", 1);
            result.put("size", total);
            result.put("totalPages", 1);
        } else {
            int totalPages = Math.max(1, (int) Math.ceil((double) total / size));
            int fromIndex = Math.min((page - 1) * size, total);
            int toIndex = Math.min(fromIndex + size, total);
            result.put("data", statsList.subList(fromIndex, toIndex));
            result.put("page", page);
            result.put("size", size);
            result.put("totalPages", totalPages);
        }
        return result;
    }

    private List<UsageLog> filterLogs(List<UsageLog> logs, String username, String modelName, String startDate, String endDate) {
        if (username != null && !username.isEmpty()) {
            logs = logs.stream().filter(l -> username.equals(l.getUsername())).collect(Collectors.toList());
        }
        if (modelName != null && !modelName.isEmpty()) {
            logs = logs.stream().filter(l -> modelName.equals(l.getModelName())).collect(Collectors.toList());
        }
        boolean hasStart = startDate != null && !startDate.isEmpty();
        boolean hasEnd = endDate != null && !endDate.isEmpty();
        if (hasStart || hasEnd) {
            logs = logs.stream().filter(l -> {
                String ts = l.getTimestamp();
                if (ts == null || ts.length() < 10) return false;
                String d = ts.substring(0, 10);
                if (hasStart && d.compareTo(startDate) < 0) return false;
                if (hasEnd && d.compareTo(endDate) > 0) return false;
                return true;
            }).collect(Collectors.toList());
        }
        return logs;
    }

    private Map<String, Object> validateDateRange(String startDate, String endDate) {
        boolean hasStart = startDate != null && !startDate.isEmpty();
        boolean hasEnd = endDate != null && !endDate.isEmpty();
        if (!hasStart && !hasEnd) return null;
        if (hasStart && !startDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "开始日期格式错误，应为 yyyy-MM-dd");
            return err;
        }
        if (hasEnd && !endDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "结束日期格式错误，应为 yyyy-MM-dd");
            return err;
        }
        String s = hasStart ? startDate : endDate;
        String e = hasEnd ? endDate : startDate;
        try {
            LocalDate start = LocalDate.parse(s);
            LocalDate end = LocalDate.parse(e);
            if (end.isBefore(start)) {
                Map<String, Object> err = new HashMap<>();
                err.put("success", false);
                err.put("message", "结束日期不能早于开始日期");
                return err;
            }
            long days = ChronoUnit.DAYS.between(start, end) + 1;
            if (days > 30) {
                Map<String, Object> err = new HashMap<>();
                err.put("success", false);
                err.put("message", "统计时间范围不能超过 30 天（当前 " + days + " 天）");
                return err;
            }
        } catch (Exception ex) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "日期解析失败，请使用 yyyy-MM-dd 格式");
            return err;
        }
        return null;
    }
}
