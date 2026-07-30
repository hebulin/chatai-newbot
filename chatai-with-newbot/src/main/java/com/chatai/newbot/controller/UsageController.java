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
        if (page < 1) page = 1;
        if (size < 1) size = 20;
        if (size > 500) size = 500;
        int total = storageService.countUsageLogs(effectiveUsername, modelName, startDate, endDate);
        int totalPages = Math.max(1, (int) Math.ceil((double) total / size));
        int offset = Math.min((page - 1) * size, total);
        List<UsageLog> logs = storageService.queryUsageLogs(effectiveUsername, modelName, startDate, endDate, offset, size);

        result.put("success", true);
        result.put("data", logs);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("totalPages", totalPages);
        result.put("isAdmin", isAdmin(request));
        return result;
    }

    /**
     * 使用汇总 - 返回总调用次数、输入/输出/思考Token汇总
     */
    @GetMapping("/summary")
    public Map<String, Object> getUsageSummary(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        String effectiveUsername = resolveUsernameScope(request, username);
        Map<String, Long> sums = storageService.summarizeUsage(effectiveUsername, startDate, endDate);

        Map<String, Object> data = new HashMap<>();
        data.put("calls", sums.getOrDefault("calls", 0L));
        data.put("promptTokens", sums.getOrDefault("promptTokens", 0L));
        data.put("completionTokens", sums.getOrDefault("completionTokens", 0L));
        data.put("reasoningTokens", sums.getOrDefault("reasoningTokens", 0L));

        result.put("success", true);
        result.put("data", data);
        return result;
    }

    /**
     * 筛选选项 - 管理员返回全部用户名/模型名；普通用户仅返回本人及其使用过的模型
     */
    @GetMapping("/filters")
    public Map<String, Object> getUsageFilters(HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        User user = (User) request.getAttribute("currentUser");

        List<String> usernames;
        List<String> modelNames;
        if (user != null && user.isAdmin()) {
            usernames = storageService.getUsageUsernames();
            modelNames = storageService.getUsageModelNames(null);
        } else {
            String me = user != null ? user.getUsername() : "";
            usernames = Collections.singletonList(me);
            modelNames = storageService.getUsageModelNames(me);
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
     * usernames 支持多用户筛选（仅管理员，逗号分隔，用于多用户对比图表）
     */
    @GetMapping("/stats")
    public Map<String, Object> getUsageStats(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String usernames,
            @RequestParam(required = false) String modelName,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "false") boolean getAll,
            HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> rangeCheck = validateDateRange(startDate, endDate);
        if (rangeCheck != null) return rangeCheck;

        String effectiveUsername = resolveUsernameScope(request, username);

        // 用户范围：普通用户强制本人；管理员支持多用户筛选（逗号分隔）或单用户筛选
        List<String> nameList = null;
        if (isAdmin(request) && usernames != null && !usernames.isEmpty()) {
            List<String> parsed = Arrays.stream(usernames.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .collect(Collectors.toList());
            if (!parsed.isEmpty()) nameList = parsed;
        }
        if (nameList == null && effectiveUsername != null && !effectiveUsername.isEmpty()) {
            nameList = Collections.singletonList(effectiveUsername);
        }

        List<Map<String, Object>> statsList = storageService.aggregateUsageStats(nameList, modelName, startDate, endDate);

        int total = statsList.size();
        if (page < 1) page = 1;
        if (size < 1) size = 20;
        if (size > 500) size = 500;
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
