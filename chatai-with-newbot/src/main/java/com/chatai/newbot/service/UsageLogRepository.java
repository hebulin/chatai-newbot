package com.chatai.newbot.service;

import com.chatai.newbot.model.UsageLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** SQLite 用量日志仓储，负责筛选、分页与聚合 SQL。 */
@Repository
public class UsageLogRepository {
    private static final int FETCH_LIMIT = 10000;
    private static final String STATS_SELECT =
            "SELECT COALESCE(username,'未知') AS username, COALESCE(SUBSTR(timestamp,1,10),'未知') AS date, " +
            "COALESCE(model_name,'未知') AS model_name, COALESCE(model_id,'') AS model_id, COUNT(*) AS count, " +
            "COALESCE(SUM(prompt_tokens),0) AS prompt_tokens, COALESCE(SUM(completion_tokens),0) AS completion_tokens, " +
            "COALESCE(SUM(cached_tokens),0) AS cached_tokens, COALESCE(SUM(reasoning_tokens),0) AS reasoning_tokens, " +
            "COALESCE(SUM(cost_cny),0) AS cost_cny, COUNT(cost_cny) AS cost_snapshots, " +
            "COALESCE(SUM(CASE WHEN cost_cny IS NULL THEN prompt_tokens ELSE 0 END),0) AS unpriced_prompt_tokens, " +
            "COALESCE(SUM(CASE WHEN cost_cny IS NULL THEN completion_tokens ELSE 0 END),0) AS unpriced_completion_tokens, " +
            "COALESCE(SUM(CASE WHEN cost_cny IS NULL THEN cached_tokens ELSE 0 END),0) AS unpriced_cached_tokens, " +
            "COALESCE(SUM(CASE WHEN cost_cny IS NULL THEN reasoning_tokens ELSE 0 END),0) AS unpriced_reasoning_tokens, " +
            "COALESCE(SUM(deep_thinking),0) AS thinking_count FROM t_usage_log";
    private static final String STATS_GROUP_ORDER =
            " GROUP BY 1, 2, 3, 4 ORDER BY date DESC, username ASC, model_name ASC";

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<UsageLog> rowMapper = (resultSet, rowNumber) -> {
        UsageLog usage = new UsageLog();
        usage.setRequestId(resultSet.getString("request_id"));
        usage.setUserId(resultSet.getString("user_id"));
        usage.setUsername(resultSet.getString("username"));
        usage.setModelId(resultSet.getString("model_id"));
        usage.setModelName(resultSet.getString("model_name"));
        usage.setTimestamp(resultSet.getString("timestamp"));
        usage.setPromptTokens(resultSet.getInt("prompt_tokens"));
        usage.setCompletionTokens(resultSet.getInt("completion_tokens"));
        usage.setCachedTokens(resultSet.getInt("cached_tokens"));
        usage.setReasoningTokens(resultSet.getInt("reasoning_tokens"));
        usage.setDeepThinking(resultSet.getInt("deep_thinking") == 1);
        double cost = resultSet.getDouble("cost_cny");
        usage.setCostCny(resultSet.wasNull() ? null : cost);
        return usage;
    };

    /** 创建用量日志仓储。 */
    public UsageLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 新增一条用量日志。 */
    public void add(UsageLog usage) {
        jdbcTemplate.update(
                "INSERT INTO t_usage_log (request_id, user_id, username, model_id, model_name, timestamp, prompt_tokens, completion_tokens, cached_tokens, reasoning_tokens, deep_thinking, cost_cny) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                usage.getRequestId(), usage.getUserId(), usage.getUsername(), usage.getModelId(), usage.getModelName(),
                usage.getTimestamp(), usage.getPromptTokens(), usage.getCompletionTokens(), usage.getCachedTokens(),
                usage.getReasoningTokens(), usage.isDeepThinking() ? 1 : 0, usage.getCostCny());
    }

    /** 查询最近的用量日志。 */
    public List<UsageLog> findRecent() {
        return jdbcTemplate.query("SELECT * FROM t_usage_log ORDER BY timestamp DESC LIMIT " + FETCH_LIMIT, rowMapper);
    }

    /** 查询指定用户最近的用量日志。 */
    public List<UsageLog> findRecentByUser(String userId) {
        return jdbcTemplate.query(
                "SELECT * FROM t_usage_log WHERE user_id = ? ORDER BY timestamp DESC LIMIT " + FETCH_LIMIT,
                rowMapper, userId);
    }

    /** 统计用户指定日期的调用次数。 */
    public int countByUserAndDay(String userId, String day) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_usage_log WHERE user_id = ? AND timestamp >= ? AND timestamp < ?",
                Integer.class, userId, day, nextDay(day));
        return count == null ? 0 : count;
    }

    /** 汇总用户指定日期的输入输出 Token。 */
    public long sumTokensByUserAndDay(String userId, String day) {
        Long sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(prompt_tokens + completion_tokens), 0) FROM t_usage_log WHERE user_id = ? AND timestamp >= ? AND timestamp < ?",
                Long.class, userId, day, nextDay(day));
        return sum == null ? 0L : sum;
    }

    /** 查询用户指定日期的日志，供服务层补算历史费用。 */
    public List<UsageLog> findByUserAndDay(String userId, String day) {
        return jdbcTemplate.query(
                "SELECT * FROM t_usage_log WHERE user_id = ? AND timestamp >= ? AND timestamp < ?",
                rowMapper, userId, day, nextDay(day));
    }

    /** 更新用量日志并写入服务层计算后的费用快照。 */
    public void update(UsageLog usage, double cost) {
        if (usage.getRequestId() != null && !usage.getRequestId().isBlank()) {
            jdbcTemplate.update(
                    "UPDATE t_usage_log SET prompt_tokens=?, completion_tokens=?, cached_tokens=?, reasoning_tokens=?, deep_thinking=?, cost_cny=? WHERE request_id=?",
                    usage.getPromptTokens(), usage.getCompletionTokens(), usage.getCachedTokens(), usage.getReasoningTokens(),
                    usage.isDeepThinking() ? 1 : 0, cost, usage.getRequestId());
        } else {
            jdbcTemplate.update(
                    "UPDATE t_usage_log SET prompt_tokens=?, completion_tokens=?, cached_tokens=?, reasoning_tokens=?, deep_thinking=?, cost_cny=? WHERE user_id=? AND timestamp=? AND model_id=?",
                    usage.getPromptTokens(), usage.getCompletionTokens(), usage.getCachedTokens(), usage.getReasoningTokens(),
                    usage.isDeepThinking() ? 1 : 0, cost, usage.getUserId(), usage.getTimestamp(), usage.getModelId());
        }
    }

    /** 查询存在日志的日期列表。 */
    public List<String> findDates() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT SUBSTR(timestamp, 1, 10) AS day FROM t_usage_log ORDER BY day", String.class);
    }

    /** 删除指定日期之前的日志。 */
    public int deleteBefore(String day) {
        return jdbcTemplate.update("DELETE FROM t_usage_log WHERE timestamp < ?", day);
    }

    /** 按条件分页查询用量日志。 */
    public List<UsageLog> query(String username, String modelName, String startDate, String endDate,
                                int offset, int limit) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(username, modelName, startDate, endDate, args);
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(
                "SELECT * FROM t_usage_log" + where + " ORDER BY timestamp DESC LIMIT ? OFFSET ?",
                rowMapper, args.toArray());
    }

    /** 统计筛选条件命中的日志数量。 */
    public int count(String username, String modelName, String startDate, String endDate) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(username, modelName, startDate, endDate, args);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_usage_log" + where, Integer.class, args.toArray());
        return count == null ? 0 : count;
    }

    /** 汇总筛选范围内的调用与 Token 指标。 */
    public Map<String, Long> summarize(String username, String startDate, String endDate) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(username, null, startDate, endDate, args);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT COUNT(*) AS calls, COALESCE(SUM(prompt_tokens),0) AS prompt_tokens, " +
                        "COALESCE(SUM(completion_tokens),0) AS completion_tokens, " +
                        "COALESCE(SUM(reasoning_tokens),0) AS reasoning_tokens FROM t_usage_log" + where,
                args.toArray());
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("calls", ((Number) row.get("calls")).longValue());
        result.put("promptTokens", ((Number) row.get("prompt_tokens")).longValue());
        result.put("completionTokens", ((Number) row.get("completion_tokens")).longValue());
        result.put("reasoningTokens", ((Number) row.get("reasoning_tokens")).longValue());
        return result;
    }

    /** 聚合统计全部匹配分组。 */
    public List<Map<String, Object>> aggregate(List<String> usernames, String modelName,
                                               String startDate, String endDate) {
        return aggregateInternal(usernames, modelName, startDate, endDate, null, null);
    }

    /** 分页聚合统计匹配分组。 */
    public List<Map<String, Object>> aggregate(List<String> usernames, String modelName,
                                               String startDate, String endDate, int offset, int limit) {
        return aggregateInternal(usernames, modelName, startDate, endDate, offset, limit);
    }

    /** 统计聚合分组数量。 */
    public int countGroups(List<String> usernames, String modelName, String startDate, String endDate) {
        List<Object> args = new ArrayList<>();
        String where = buildStatsWhere(usernames, modelName, startDate, endDate, args);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (SELECT 1 FROM t_usage_log" + where + " GROUP BY " +
                        "COALESCE(username,'未知'), COALESCE(SUBSTR(timestamp,1,10),'未知'), COALESCE(model_name,'未知'), COALESCE(model_id,''))",
                Integer.class, args.toArray());
        return count == null ? 0 : count;
    }

    /** 查询日志中的用户名选项。 */
    public List<String> findUsernames() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT username FROM t_usage_log WHERE username IS NOT NULL ORDER BY username", String.class);
    }

    /** 查询日志中的模型名称选项，可限定用户名。 */
    public List<String> findModelNames(String username) {
        if (username != null && !username.isEmpty()) {
            return jdbcTemplate.queryForList(
                    "SELECT DISTINCT model_name FROM t_usage_log WHERE model_name IS NOT NULL AND username = ? ORDER BY model_name",
                    String.class, username);
        }
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT model_name FROM t_usage_log WHERE model_name IS NOT NULL ORDER BY model_name", String.class);
    }

    /** 执行聚合查询并转换数据库列名。 */
    private List<Map<String, Object>> aggregateInternal(List<String> usernames, String modelName,
                                                        String startDate, String endDate,
                                                        Integer offset, Integer limit) {
        List<Object> args = new ArrayList<>();
        String where = buildStatsWhere(usernames, modelName, startDate, endDate, args);
        String paging = "";
        if (offset != null && limit != null) {
            paging = " LIMIT ? OFFSET ?";
            args.add(limit);
            args.add(offset);
        }
        return mapStats(jdbcTemplate.queryForList(STATS_SELECT + where + STATS_GROUP_ORDER + paging, args.toArray()));
    }

    /** 构造普通日志筛选条件。 */
    private String buildWhere(String username, String modelName, String startDate, String endDate,
                              List<Object> args) {
        List<String> conditions = new ArrayList<>();
        if (username != null && !username.isEmpty()) { conditions.add("username = ?"); args.add(username); }
        if (modelName != null && !modelName.isEmpty()) { conditions.add("model_name = ?"); args.add(modelName); }
        if (startDate != null && !startDate.isEmpty()) { conditions.add("timestamp >= ?"); args.add(startDate); }
        if (endDate != null && !endDate.isEmpty()) {
            conditions.add("timestamp < ?");
            args.add(LocalDate.parse(endDate).plusDays(1).toString());
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    /** 构造带用户名集合的聚合筛选条件。 */
    private String buildStatsWhere(List<String> usernames, String modelName, String startDate,
                                   String endDate, List<Object> args) {
        String where = buildWhere(null, modelName, startDate, endDate, args);
        if (usernames != null && !usernames.isEmpty()) {
            where += (where.isEmpty() ? " WHERE " : " AND ") +
                    "username IN (" + usernames.stream().map(value -> "?").collect(Collectors.joining(",")) + ")";
            args.addAll(usernames);
        }
        return where;
    }

    /** 将聚合数据库行转换为前端约定的驼峰字段。 */
    private List<Map<String, Object>> mapStats(List<Map<String, Object>> rows) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new HashMap<>();
            item.put("username", row.get("username"));
            item.put("date", row.get("date"));
            item.put("modelName", row.get("model_name"));
            item.put("modelId", row.get("model_id"));
            item.put("count", ((Number) row.get("count")).intValue());
            item.put("promptTokens", ((Number) row.get("prompt_tokens")).intValue());
            item.put("completionTokens", ((Number) row.get("completion_tokens")).intValue());
            item.put("cachedTokens", ((Number) row.get("cached_tokens")).intValue());
            item.put("reasoningTokens", ((Number) row.get("reasoning_tokens")).intValue());
            item.put("costCny", ((Number) row.get("cost_cny")).doubleValue());
            item.put("costSnapshots", ((Number) row.get("cost_snapshots")).longValue());
            item.put("unpricedPromptTokens", ((Number) row.get("unpriced_prompt_tokens")).intValue());
            item.put("unpricedCompletionTokens", ((Number) row.get("unpriced_completion_tokens")).intValue());
            item.put("unpricedCachedTokens", ((Number) row.get("unpriced_cached_tokens")).intValue());
            item.put("unpricedReasoningTokens", ((Number) row.get("unpriced_reasoning_tokens")).intValue());
            item.put("thinkingCount", ((Number) row.get("thinking_count")).longValue());
            results.add(item);
        }
        return results;
    }

    /** 计算下一天的范围上界，解析失败时保持兼容返回原值。 */
    private String nextDay(String day) {
        try { return LocalDate.parse(day).plusDays(1).toString(); }
        catch (Exception exception) { return day; }
    }
}
