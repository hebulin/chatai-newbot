/* ============================================
   USAGE STATS — index 页面"数据统计"弹窗
   管理员：全站视图（可筛选任意用户）
   普通用户：个人视图（后端强制仅返回本人数据）
   依赖 chat.js 全局：authHeaders / escapeHtml / fmtToken /
   showToast / authFailMsg / safeStorageGet / models / providerIconMap
   ============================================ */
(function () {
    'use strict';

    var S = {
        layerIndex: null,
        onResize: null,
        isAdmin: false,
        laypage: null,
        usernames: [],
        modelNames: [],
        usagePage: 1,
        usageSize: 10,
        statsPage: 1,
        statsSize: 10,
        statsTableLoaded: false,
        chartData: [],
        lineMetric: 'count',
        barMetric: 'totalTokens',
        subTab: 'usage'
    };

    var METRICS = {
        count: '调用次数',
        promptTokens: '输入Token',
        completionTokens: '输出Token',
        reasoningTokens: '思考Token',
        cachedTokens: '缓存Token',
        thinkingCount: '思考模式',
        totalTokens: 'Token总量'
    };

    function esc(v) {
        var s = (v === undefined || v === null) ? '' : String(v);
        if (window.escapeHtml) s = window.escapeHtml(s);
        return s.replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }
    function fmt(n) { return window.fmtToken ? window.fmtToken(n) : String(n == null ? 0 : n); }
    function fmtShort(n) {
        if (n == null) return '0';
        if (n >= 1e6) return (n / 1e6).toFixed(1) + 'M';
        if (n >= 1e3) return (n / 1e3).toFixed(1) + 'K';
        return String(n);
    }

    function usFetch(url) {
        return fetch(url, { headers: window.authHeaders ? window.authHeaders() : {} }).then(function (r) {
            if (r.status === 401) {
                if (window.showToast) window.showToast(window.authFailMsg ? window.authFailMsg(r) : '登录已过期');
                setTimeout(function () { window.location.href = '/login.html'; }, 1200);
                throw new Error('auth');
            }
            return r.json();
        });
    }

    /* ---------- 图标 ---------- */
    function inferProviderId(name) {
        if (!name) return '';
        var ml = String(name).toLowerCase();
        if (ml.indexOf('deepseek') >= 0) return 'deepseek';
        if (ml.indexOf('qwen') >= 0) return 'qwen';
        if (ml.indexOf('kimi') >= 0 || ml.indexOf('moonshot') >= 0) return 'kimi';
        if (ml.indexOf('glm') >= 0 || ml.indexOf('chatglm') >= 0 || ml.indexOf('zhipu') >= 0) return 'zhipu';
        if (ml.indexOf('minimax') >= 0 || ml.indexOf('abab') >= 0) return 'minimax';
        if (ml.indexOf('doubao') >= 0) return 'doubao';
        return '';
    }
    function entityIcon(providerId, emojiIcon, size) {
        size = size || 18;
        var map = window.providerIconMap || {};
        var inner = '';
        if (providerId && providerId !== '__custom__' && map[providerId]) {
            inner = '<img src="' + map[providerId] + '" style="width:' + size + 'px;height:' + size + 'px;object-fit:contain;border-radius:4px;" onerror="this.style.display=\'none\'"/>';
        } else if (emojiIcon) {
            inner = '<span style="font-size:' + size + 'px;line-height:1;">' + esc(emojiIcon) + '</span>';
        }
        return '<span class="us-opt-icon" style="width:' + size + 'px;height:' + size + 'px;">' + inner + '</span>';
    }
    function modelIcon(modelName) {
        var models = window.models || [];
        var m = null;
        if (models.length) {
            m = models.find(function (x) { return (x.displayName || '') === modelName; });
            if (!m) m = models.find(function (x) { return (x.modelId || '') === modelName; });
        }
        if (m) return entityIcon(m.providerId, m.providerIcon, 18);
        var pid = inferProviderId(modelName);
        if (pid) return entityIcon(pid, null, 18);
        return '<span class="us-opt-icon" style="width:18px;height:18px;"></span>';
    }

    /* ---------- 弹窗构建 ---------- */
    function buildSelect(areaId, defaultText) {
        return '<div class="us-select-area" id="' + areaId + '">' +
            '<div class="us-select-trigger" onclick="UsageStats.toggleDropdown(this, event)">' +
            '<span class="us-select-value">' + esc(defaultText) + '</span>' +
            '<svg class="us-select-arrow" width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><polyline points="6 9 12 15 18 9"/></svg>' +
            '</div>' +
            '<div class="us-select-dropdown"></div>' +
            '<input type="hidden" value=""/>' +
            '</div>';
    }

    function userFieldHtml(areaId) {
        if (!S.isAdmin) return '';
        return '<div class="us-field">' +
            '<label class="us-label">用户</label>' +
            buildSelect(areaId, '全部用户') +
            '</div>';
    }

    function buildSearchBar(prefix) {
        var modelAreaId = prefix + 'ModelArea';
        return '<div class="us-search-bar">' +
            userFieldHtml(prefix + 'UserArea') +
            '<div class="us-field"><label class="us-label">模型</label>' + buildSelect(modelAreaId, '全部模型') + '</div>' +
            '<div class="us-field"><label class="us-label">开始日期</label><input type="date" id="' + prefix + 'StartDate" class="us-input" autocomplete="off"/></div>' +
            '<div class="us-field"><label class="us-label">结束日期</label><input type="date" id="' + prefix + 'EndDate" class="us-input" autocomplete="off"/></div>' +
            '<div class="us-actions">' +
            '<button type="button" class="us-btn us-btn-primary" onclick="UsageStats.search(\'' + prefix + '\')">' +
            '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>搜索</button>' +
            '<button type="button" class="us-btn" onclick="UsageStats.reset(\'' + prefix + '\')">重置</button>' +
            '</div></div>';
    }

    function buildModalHtml() {
        var scopeBadge = S.isAdmin
            ? '<span class="us-scope-badge us-scope-admin">全站视图 · ALL USERS</span>'
            : '<span class="us-scope-badge us-scope-self">个人视图 · ONLY ME</span>';
        var userCol = S.isAdmin ? '<th>用户</th>' : '';
        var h = '';
        h += '<div class="us-modal">';
        /* 头部 */
        h += '<div class="us-header">';
        h += '<div class="us-header-top"><span class="us-eyebrow"><b>ANALYTICS</b> · 数据统计</span>' + scopeBadge + '</div>';
        h += '<div class="us-summary-strip">';
        h += '<div class="us-summary-cell" data-kind="calls"><div class="us-summary-label">调用次数 · CALLS</div><div class="us-summary-value" id="usSumCalls">—</div></div>';
        h += '<div class="us-summary-cell" data-kind="prompt"><div class="us-summary-label">输入Token · INPUT</div><div class="us-summary-value" id="usSumPrompt">—</div></div>';
        h += '<div class="us-summary-cell" data-kind="output"><div class="us-summary-label">输出Token · OUTPUT</div><div class="us-summary-value" id="usSumOutput">—</div></div>';
        h += '<div class="us-summary-cell" data-kind="reason"><div class="us-summary-label">思考Token · REASONING</div><div class="us-summary-value" id="usSumReason">—</div></div>';
        h += '</div></div>';
        /* 主体 */
        h += '<div class="us-body">';
        h += '<div class="us-subtab-nav">';
        h += '<div class="us-subtab-item active" onclick="UsageStats.switchSubTab(\'usage\', this)">使用记录</div>';
        h += '<div class="us-subtab-item" onclick="UsageStats.switchSubTab(\'stats\', this)">用户统计</div>';
        h += '</div>';
        /* 使用记录 */
        h += '<div class="us-sub-content active" id="usSubUsage">';
        h += buildSearchBar('usUsage');
        h += '<div class="us-table-wrap" id="usUsageWrap"><table class="us-table"><thead><tr>';
        h += '<th>时间</th>' + userCol + '<th>模型</th><th>输入Token</th><th>输出Token</th><th>思考Token</th><th>缓存Token</th><th>思考模式</th>';
        h += '</tr></thead><tbody id="usUsageTbody"><tr><td colspan="' + (S.isAdmin ? 8 : 7) + '" class="us-loading">LOADING</td></tr></tbody></table></div>';
        h += '<div class="us-pagination" id="usUsagePager"></div>';
        h += '</div>';
        /* 用户统计 */
        h += '<div class="us-sub-content" id="usSubStats">';
        h += buildSearchBar('usStats');
        h += '<div class="us-mini-tab-nav">';
        h += '<div class="us-mini-tab-item active" onclick="UsageStats.switchMiniTab(\'list\', this)">结果列表</div>';
        h += '<div class="us-mini-tab-item" onclick="UsageStats.switchMiniTab(\'line\', this)">曲线图</div>';
        h += '<div class="us-mini-tab-item" onclick="UsageStats.switchMiniTab(\'bar\', this)">柱状图</div>';
        h += '</div>';
        h += '<div class="us-mini-tab-content active" id="usMiniList">';
        h += '<div class="us-table-wrap" id="usStatsWrap"><table class="us-table"><thead><tr>';
        h += userCol + '<th>日期</th><th>模型</th><th>调用次数</th><th>输入Token</th><th>输出Token</th><th>思考Token</th><th>缓存Token</th><th>思考模式次数</th>';
        h += '</tr></thead><tbody id="usStatsTbody"><tr><td colspan="' + (S.isAdmin ? 9 : 8) + '" class="us-empty">点击「搜索」查看统计结果</td></tr></tbody></table></div>';
        h += '<div class="us-pagination" id="usStatsPager"></div>';
        h += '</div>';
        h += '<div class="us-mini-tab-content" id="usMiniLine">';
        h += metricToolbar('line');
        h += '<div id="usLineChart" class="us-chart-canvas"><div class="us-chart-empty">暂无统计数据，请先搜索</div></div>';
        h += '</div>';
        h += '<div class="us-mini-tab-content" id="usMiniBar">';
        h += metricToolbar('bar');
        h += '<div id="usBarChart" class="us-chart-canvas"><div class="us-chart-empty">暂无统计数据，请先搜索</div></div>';
        h += '</div>';
        h += '</div>';
        h += '</div></div>';
        return h;
    }

    function metricToolbar(kind) {
        var items = kind === 'line'
            ? [['count', '调用次数'], ['promptTokens', '输入Token'], ['completionTokens', '输出Token'], ['reasoningTokens', '思考Token'], ['cachedTokens', '缓存Token'], ['thinkingCount', '思考模式']]
            : [['totalTokens', 'Token总量'], ['count', '调用次数'], ['promptTokens', '输入Token'], ['completionTokens', '输出Token'], ['reasoningTokens', '思考Token'], ['cachedTokens', '缓存Token']];
        var active = kind === 'line' ? S.lineMetric : S.barMetric;
        var fn = kind === 'line' ? 'switchLineMetric' : 'switchBarMetric';
        var html = '<div class="us-chart-toolbar"><span class="us-metric-label">指标</span><div class="us-metric-selector">';
        items.forEach(function (it) {
            html += '<button type="button" class="us-metric-btn' + (it[0] === active ? ' active' : '') + '" onclick="UsageStats.' + fn + '(\'' + it[0] + '\', this)">' + it[1] + '</button>';
        });
        html += '</div></div>';
        return html;
    }

    /* ---------- 下拉选 ---------- */
    function closeAllDropdowns() {
        document.querySelectorAll('.us-select-area.active').forEach(function (a) { a.classList.remove('active'); });
    }
    function onDocClick(e) {
        var area = e.target && e.target.closest ? e.target.closest('.us-select-area') : null;
        if (!area) closeAllDropdowns();
    }
    function toggleDropdown(triggerEl, e) {
        if (e && e.stopPropagation) e.stopPropagation();
        var area = triggerEl.closest('.us-select-area');
        var wasActive = area.classList.contains('active');
        closeAllDropdowns();
        if (!wasActive) area.classList.add('active');
    }
    function selectOption(optEl, e) {
        if (e && e.stopPropagation) e.stopPropagation();
        var area = optEl.closest('.us-select-area');
        var value = optEl.getAttribute('data-value') || '';
        var label = optEl.getAttribute('data-label') || '';
        area.querySelector('.us-select-value').textContent = label;
        area.querySelector('input[type="hidden"]').value = value;
        area.querySelectorAll('.us-select-option').forEach(function (o) { o.classList.remove('selected'); });
        optEl.classList.add('selected');
        area.classList.remove('active');
    }
    function renderSelectOptions(areaId, items, defaultText) {
        var area = document.getElementById(areaId);
        if (!area) return;
        var dropdown = area.querySelector('.us-select-dropdown');
        var html = '<div class="us-select-option selected" data-value="" data-label="' + esc(defaultText) + '" onclick="UsageStats.selectOption(this, event)">' +
            '<span class="us-opt-icon" style="width:18px;height:18px;"></span><span class="us-opt-text">' + esc(defaultText) + '</span></div>';
        items.forEach(function (it) {
            html += '<div class="us-select-option" data-value="' + esc(it.value) + '" data-label="' + esc(it.text) + '" onclick="UsageStats.selectOption(this, event)">' +
                (it.icon || '<span class="us-opt-icon" style="width:18px;height:18px;"></span>') +
                '<span class="us-opt-text">' + esc(it.text) + '</span></div>';
        });
        dropdown.innerHTML = html;
        area.querySelector('.us-select-value').textContent = defaultText;
        area.querySelector('input[type="hidden"]').value = '';
    }
    function resetSelect(areaId, defaultText) {
        var area = document.getElementById(areaId);
        if (!area) return;
        area.querySelector('.us-select-value').textContent = defaultText;
        area.querySelector('input[type="hidden"]').value = '';
        area.querySelectorAll('.us-select-option').forEach(function (o) { o.classList.remove('selected'); });
        var first = area.querySelector('.us-select-option');
        if (first) first.classList.add('selected');
    }

    /* ---------- 数据加载 ---------- */
    function loadFilters() {
        usFetch('/api/usage/filters').then(function (data) {
            if (!data || !data.success) return;
            if (typeof data.isAdmin === 'boolean') S.isAdmin = data.isAdmin;
            S.usernames = data.usernames || [];
            S.modelNames = data.modelNames || [];
            if (S.isAdmin) {
                renderSelectOptions('usUsageUserArea', S.usernames.map(function (u) { return { value: u, text: u }; }), '全部用户');
                renderSelectOptions('usStatsUserArea', S.usernames.map(function (u) { return { value: u, text: u }; }), '全部用户');
            }
            var modelItems = S.modelNames.map(function (mn) { return { value: mn, text: mn, icon: modelIcon(mn) }; });
            renderSelectOptions('usUsageModelArea', modelItems, '全部模型');
            renderSelectOptions('usStatsModelArea', modelItems, '全部模型');
        }).catch(function () { });
    }

    function readFilters(prefix) {
        function val(id) { var el = document.getElementById(id); return el ? (el.value || '') : ''; }
        function hidden(areaId) {
            var area = document.getElementById(areaId);
            if (!area) return '';
            var inp = area.querySelector('input[type="hidden"]');
            return inp ? (inp.value || '') : '';
        }
        return {
            username: hidden(prefix + 'UserArea'),
            modelName: hidden(prefix + 'ModelArea'),
            startDate: val(prefix + 'StartDate'),
            endDate: val(prefix + 'EndDate')
        };
    }

    function appendFilterParams(url, f) {
        if (f.username) url += '&username=' + encodeURIComponent(f.username);
        if (f.modelName) url += '&modelName=' + encodeURIComponent(f.modelName);
        if (f.startDate) url += '&startDate=' + encodeURIComponent(f.startDate);
        if (f.endDate) url += '&endDate=' + encodeURIComponent(f.endDate);
        return url;
    }

    function loadUsage() {
        var f = readFilters('usUsage');
        var url = appendFilterParams('/api/usage?page=' + S.usagePage + '&size=' + S.usageSize, f);
        usFetch(url).then(function (data) {
            if (data && data.success) {
                renderUsageTable(data.data || []);
                renderPager('usUsagePager', data.total || 0, S.usagePage, S.usageSize, function (p) { S.usagePage = p; loadUsage(); });
            }
        }).catch(function () { });
    }

    function loadUsageStats() {
        var f = readFilters('usStats');
        var url = appendFilterParams('/api/usage/stats?page=' + S.statsPage + '&size=' + S.statsSize, f);
        usFetch(url).then(function (data) {
            if (data && data.success) {
                renderStatsTable(data.data || []);
                renderPager('usStatsPager', data.total || 0, S.statsPage, S.statsSize, function (p) { S.statsPage = p; loadUsageStats(); });
            }
        }).catch(function () { });
    }

    function loadCharts() {
        var f = readFilters('usStats');
        var url = appendFilterParams('/api/usage/stats?getAll=true&size=10000', f);
        usFetch(url).then(function (data) {
            if (data && data.success) {
                S.chartData = data.data || [];
                updateSummary(S.chartData);
                renderLineChart();
                renderBarChart();
            } else if (data && data.message) {
                updateSummary([]);
                var tip = '<div class="us-chart-empty" style="color:var(--warn)">' + esc(data.message) + '</div>';
                var line = document.getElementById('usLineChart');
                var bar = document.getElementById('usBarChart');
                if (line) line.innerHTML = tip;
                if (bar) bar.innerHTML = tip;
            }
        }).catch(function () { });
    }

    /* ---------- 汇总指标 ---------- */
    function updateSummary(rows) {
        var calls = 0, prompt = 0, output = 0, reason = 0;
        rows.forEach(function (r) {
            calls += r.count || 0;
            prompt += r.promptTokens || 0;
            output += r.completionTokens || 0;
            reason += r.reasoningTokens || 0;
        });
        setSummaryCell('usSumCalls', calls);
        setSummaryCell('usSumPrompt', prompt);
        setSummaryCell('usSumOutput', output);
        setSummaryCell('usSumReason', reason);
    }
    function setSummaryCell(id, value) {
        var el = document.getElementById(id);
        if (!el) return;
        el.textContent = fmt(value);
        el.classList.remove('us-tick');
        void el.offsetWidth;
        el.classList.add('us-tick');
    }

    /* ---------- 表格渲染 ---------- */
    function renderUsageTable(items) {
        var tbody = document.getElementById('usUsageTbody');
        if (!tbody) return;
        if (!items || !items.length) {
            tbody.innerHTML = '<tr><td colspan="' + (S.isAdmin ? 8 : 7) + '" class="us-empty">暂无使用记录</td></tr>';
            return;
        }
        var html = '';
        items.forEach(function (r) {
            html += '<tr><td>' + esc(r.timestamp || '-') + '</td>';
            if (S.isAdmin) html += '<td>' + esc(r.username || '-') + '</td>';
            html += '<td><span class="us-model-cell">' + modelIcon(r.modelName) + '<span>' + esc(r.modelName || '-') + '</span></span></td>';
            html += '<td>' + fmt(r.promptTokens) + '</td>';
            html += '<td>' + fmt(r.completionTokens) + '</td>';
            html += '<td>' + fmt(r.reasoningTokens) + '</td>';
            html += '<td>' + fmt(r.cachedTokens) + '</td>';
            html += '<td>' + (r.deepThinking ? '<span class="us-think-badge">思考</span>' : '-') + '</td></tr>';
        });
        tbody.innerHTML = html;
        setupScrollShadow(document.getElementById('usUsageWrap'));
    }

    function renderStatsTable(items) {
        var tbody = document.getElementById('usStatsTbody');
        if (!tbody) return;
        if (!items || !items.length) {
            tbody.innerHTML = '<tr><td colspan="' + (S.isAdmin ? 9 : 8) + '" class="us-empty">暂无统计数据</td></tr>';
            return;
        }
        var html = '';
        items.forEach(function (r) {
            html += '<tr>';
            if (S.isAdmin) html += '<td>' + esc(r.username || '-') + '</td>';
            html += '<td>' + esc(r.date || '-') + '</td>';
            html += '<td><span class="us-model-cell">' + modelIcon(r.modelName) + '<span>' + esc(r.modelName || '-') + '</span></span></td>';
            html += '<td>' + (r.count || 0) + '</td>';
            html += '<td>' + fmt(r.promptTokens) + '</td>';
            html += '<td>' + fmt(r.completionTokens) + '</td>';
            html += '<td>' + fmt(r.reasoningTokens) + '</td>';
            html += '<td>' + fmt(r.cachedTokens) + '</td>';
            html += '<td>' + (r.thinkingCount || 0) + '</td></tr>';
        });
        tbody.innerHTML = html;
        setupScrollShadow(document.getElementById('usStatsWrap'));
    }

    function setupScrollShadow(wrap) {
        if (!wrap || wrap._usShadowBound) return;
        wrap._usShadowBound = true;
        function update() { wrap.classList.toggle('is-scrolled', wrap.scrollLeft > 2); }
        wrap.addEventListener('scroll', update, { passive: true });
        update();
    }

    /* ---------- 分页 ---------- */
    function renderPager(elemId, total, curr, size, cb) {
        var el = document.getElementById(elemId);
        if (!el) return;
        if (!S.laypage) {
            if (window.layui) {
                layui.use(['laypage'], function () {
                    S.laypage = layui.laypage;
                    renderPager(elemId, total, curr, size, cb);
                });
            }
            return;
        }
        var isMobile = window.innerWidth <= 480;
        S.laypage.render({
            elem: elemId,
            count: total,
            limit: size,
            curr: curr,
            theme: '#4285f4',
            groups: isMobile ? 3 : 5,
            layout: isMobile ? ['prev', 'page', 'next'] : ['prev', 'page', 'next', 'count'],
            jump: function (obj, first) { if (!first) cb(obj.curr); }
        });
    }

    /* ---------- 图表 ---------- */
    function metricValue(r, metric) {
        if (metric === 'totalTokens') return (r.promptTokens || 0) + (r.completionTokens || 0) + (r.reasoningTokens || 0);
        return r[metric] || 0;
    }
    function aggregateBy(data, keyFn, metric) {
        var result = {};
        data.forEach(function (r) {
            var key = keyFn(r) || '-';
            result[key] = (result[key] || 0) + metricValue(r, metric);
        });
        return result;
    }

    function renderLineChart() {
        var container = document.getElementById('usLineChart');
        if (!container) return;
        if (!S.chartData || !S.chartData.length) {
            container.innerHTML = '<div class="us-chart-empty">暂无统计数据，请先搜索</div>';
            return;
        }
        var byDate = aggregateBy(S.chartData, function (r) { return r.date; }, S.lineMetric);
        var dates = Object.keys(byDate).sort();
        container.innerHTML = buildLineSvg(dates, dates.map(function (d) { return byDate[d]; }), S.lineMetric);
    }

    function renderBarChart() {
        var container = document.getElementById('usBarChart');
        if (!container) return;
        if (!S.chartData || !S.chartData.length) {
            container.innerHTML = '<div class="us-chart-empty">暂无统计数据，请先搜索</div>';
            return;
        }
        var byModel = aggregateBy(S.chartData, function (r) { return r.modelName; }, S.barMetric);
        var models = Object.keys(byModel).sort(function (a, b) { return byModel[b] - byModel[a]; }).slice(0, 10);
        container.innerHTML = buildBarSvg(models, models.map(function (m) { return byModel[m]; }), S.barMetric);
    }

    function buildLineSvg(labels, values, metric) {
        var w = 720, h = 320;
        var padL = 60, padR = 30, padT = 30, padB = 60;
        var chartW = w - padL - padR, chartH = h - padT - padB;
        var maxV = Math.max.apply(null, values);
        var niceMax = maxV > 0 ? Math.ceil(maxV * 1.15) : 1;
        var points = values.map(function (v, i) {
            var x = padL + (labels.length === 1 ? chartW / 2 : (i / (labels.length - 1)) * chartW);
            var y = padT + chartH - (v / niceMax) * chartH;
            return { x: x, y: y, v: v, label: labels[i] };
        });
        var valueName = METRICS[metric] || metric;
        var svg = '<svg viewBox="0 0 ' + w + ' ' + h + '" xmlns="http://www.w3.org/2000/svg" preserveAspectRatio="xMidYMid meet" style="width:100%;height:100%;">';
        for (var i = 0; i <= 4; i++) {
            var yy = padT + chartH - (i / 4) * chartH;
            svg += '<line class="us-chart-axis" x1="' + padL + '" y1="' + yy + '" x2="' + (w - padR) + '" y2="' + yy + '" stroke-opacity="0.15"/>';
            svg += '<text class="us-chart-label" x="' + (padL - 8) + '" y="' + (yy + 4) + '" text-anchor="end">' + fmtShort(niceMax * i / 4) + '</text>';
        }
        if (points.length > 1) {
            var path = 'M ' + points.map(function (p) { return p.x + ' ' + p.y; }).join(' L ');
            svg += '<path d="' + path + '" fill="none" stroke="var(--primary)" stroke-width="2.5" stroke-linejoin="round" stroke-linecap="round"/>';
            svg += '<path d="' + path + ' L ' + points[points.length - 1].x + ' ' + (padT + chartH) + ' L ' + points[0].x + ' ' + (padT + chartH) + ' Z" fill="var(--primary)" fill-opacity="0.1" stroke="none"/>';
        }
        points.forEach(function (p) {
            svg += '<circle class="us-chart-point" cx="' + p.x + '" cy="' + p.y + '" r="5" fill="var(--primary)" stroke="var(--surface)" stroke-width="2">';
            svg += '<title>' + esc(p.label) + '\n' + valueName + '：' + fmt(p.v) + '</title></circle>';
        });
        var skipStep = labels.length > 8 ? Math.ceil(labels.length / 8) : 1;
        points.forEach(function (p, i) {
            if (i % skipStep !== 0 && i !== points.length - 1) return;
            var dl = (p.label && p.label.length >= 10) ? p.label.substring(5) : (p.label || '');
            svg += '<text class="us-chart-label" x="' + p.x + '" y="' + (padT + chartH + 18) + '" text-anchor="middle">' + esc(dl) + '</text>';
        });
        svg += '<text class="us-chart-axis-label" x="' + padL + '" y="' + (h - 8) + '">' + esc(valueName + ' · 按日期趋势') + '</text>';
        svg += '</svg>';
        return svg;
    }

    function buildBarSvg(labels, values, metric) {
        var w = 720, h = 360;
        var padL = 60, padR = 20, padT = 30, padB = 110;
        var chartW = w - padL - padR, chartH = h - padT - padB;
        var maxV = Math.max.apply(null, values);
        var niceMax = maxV > 0 ? Math.ceil(maxV * 1.15) : 1;
        var step = chartW / Math.max(labels.length, 1);
        var barW = Math.min(40, step * 0.6);
        var valueName = METRICS[metric] || metric;
        var svg = '<svg viewBox="0 0 ' + w + ' ' + h + '" xmlns="http://www.w3.org/2000/svg" preserveAspectRatio="xMidYMid meet" style="width:100%;height:100%;">';
        for (var i = 0; i <= 4; i++) {
            var yy = padT + chartH - (i / 4) * chartH;
            svg += '<line class="us-chart-axis" x1="' + padL + '" y1="' + yy + '" x2="' + (w - padR) + '" y2="' + yy + '" stroke-opacity="0.15"/>';
            svg += '<text class="us-chart-label" x="' + (padL - 8) + '" y="' + (yy + 4) + '" text-anchor="end">' + fmtShort(niceMax * i / 4) + '</text>';
        }
        values.forEach(function (v, i) {
            var x = padL + i * step + (step - barW) / 2;
            var bh = (v / niceMax) * chartH;
            var y = padT + chartH - bh;
            svg += '<rect class="us-chart-bar" x="' + x + '" y="' + y + '" width="' + barW + '" height="' + bh + '" rx="3">';
            svg += '<title>' + esc(labels[i] || '') + '\n' + valueName + '：' + fmt(v) + '</title></rect>';
            svg += '<text class="us-chart-value-label" x="' + (x + barW / 2) + '" y="' + (y - 6) + '">' + fmtShort(v) + '</text>';
            var lbl = labels[i] || '';
            if (lbl.length > 14) lbl = lbl.substring(0, 13) + '…';
            svg += '<text class="us-chart-label" x="' + (x + barW / 2) + '" y="' + (padT + chartH + 16) + '" text-anchor="end" transform="rotate(-30 ' + (x + barW / 2) + ' ' + (padT + chartH + 16) + ')">' + esc(lbl) + '</text>';
        });
        svg += '<text class="us-chart-axis-label" x="' + padL + '" y="' + (h - 12) + '">' + esc(valueName + ' · 按模型 Top 10') + '</text>';
        svg += '</svg>';
        return svg;
    }

    /* ---------- 交互 ---------- */
    function switchSubTab(tab, el) {
        S.subTab = tab;
        document.querySelectorAll('.us-subtab-item').forEach(function (n) { n.classList.remove('active'); });
        if (el) el.classList.add('active');
        document.querySelectorAll('.us-sub-content').forEach(function (c) { c.classList.remove('active'); });
        if (tab === 'usage') {
            document.getElementById('usSubUsage').classList.add('active');
        } else {
            document.getElementById('usSubStats').classList.add('active');
            if (!S.statsTableLoaded) {
                S.statsTableLoaded = true;
                loadUsageStats();
            }
            renderLineChart();
            renderBarChart();
        }
    }

    function switchMiniTab(tab, el) {
        var nav = el.parentElement;
        nav.querySelectorAll('.us-mini-tab-item').forEach(function (n) { n.classList.remove('active'); });
        el.classList.add('active');
        ['list', 'line', 'bar'].forEach(function (t) {
            var c = document.getElementById('usMini' + t.charAt(0).toUpperCase() + t.slice(1));
            if (c) c.classList.toggle('active', t === tab);
        });
        if (tab === 'line') renderLineChart();
        if (tab === 'bar') renderBarChart();
    }

    function switchLineMetric(metric, el) {
        if (!METRICS[metric]) return;
        S.lineMetric = metric;
        el.parentElement.querySelectorAll('.us-metric-btn').forEach(function (n) { n.classList.remove('active'); });
        el.classList.add('active');
        renderLineChart();
    }
    function switchBarMetric(metric, el) {
        if (!METRICS[metric]) return;
        S.barMetric = metric;
        el.parentElement.querySelectorAll('.us-metric-btn').forEach(function (n) { n.classList.remove('active'); });
        el.classList.add('active');
        renderBarChart();
    }

    function validateDateRange(startDate, endDate) {
        if (!startDate && !endDate) return null;
        var s = startDate || endDate, e = endDate || startDate;
        var sd = new Date(s + 'T00:00:00'), ed = new Date(e + 'T00:00:00');
        if (isNaN(sd.getTime()) || isNaN(ed.getTime())) return '日期格式错误，请使用 yyyy-MM-dd';
        if (ed < sd) return '结束日期不能早于开始日期';
        var days = Math.floor((ed - sd) / 86400000) + 1;
        if (days > 30) return '统计时间范围不能超过 30 天（当前 ' + days + ' 天）';
        return null;
    }

    function search(prefix) {
        var f = readFilters(prefix);
        var err = validateDateRange(f.startDate, f.endDate);
        if (err) { if (window.showToast) window.showToast(err); return; }
        if (prefix === 'usUsage') {
            S.usagePage = 1;
            loadUsage();
        } else {
            S.statsPage = 1;
            S.statsTableLoaded = true;
            loadUsageStats();
            loadCharts();
        }
    }

    function reset(prefix) {
        if (S.isAdmin) resetSelect(prefix + 'UserArea', '全部用户');
        resetSelect(prefix + 'ModelArea', '全部模型');
        var s = document.getElementById(prefix + 'StartDate');
        var e = document.getElementById(prefix + 'EndDate');
        if (s) s.value = '';
        if (e) e.value = '';
        search(prefix);
    }

    /* ---------- 弹窗尺寸：按实际视口动态计算（平板/竖屏自动收缩，永不超出屏幕） ---------- */
    // 期望尺寸 920x660；视口不足时自动收缩，最小不低于 300x360
    function calcModalSize() {
        var vw = window.innerWidth, vh = window.innerHeight;
        return {
            w: Math.max(300, Math.min(920, vw - 32)),
            h: Math.max(360, Math.min(660, vh - 48))
        };
    }

    // 将弹窗居中于视口（不越界）
    function centerLayer(layero) {
        try {
            var w = layero.outerWidth(), h = layero.outerHeight();
            layero.css({
                top: Math.max((window.innerHeight - h) / 2, 16) + 'px',
                left: Math.max((window.innerWidth - w) / 2, 16) + 'px'
            });
        } catch (e) { }
    }

    // 按当前视口重设弹窗外层与内容区尺寸，并重新居中（内容区高度需扣除标题栏）
    function resizeModal(layero) {
        try {
            var s = calcModalSize();
            var titleH = layero.find('.layui-layer-title').outerHeight() || 0;
            layero.css({ width: s.w + 'px', height: s.h + 'px' });
            layero.find('.layui-layer-content').css({ height: (s.h - titleH) + 'px' });
            centerLayer(layero);
        } catch (e) { }
    }

    /* ---------- 入口 ---------- */
    function openUsageStatsModal() {
        var menu = document.getElementById('userMenu');
        if (menu) menu.style.display = 'none';
        if (!window._layer) { if (window.showToast) window.showToast('UI组件未加载完成'); return; }
        if (S.layerIndex !== null) return;

        S.isAdmin = window.safeStorageGet ? (window.safeStorageGet('role') === 'admin') : false;
        S.subTab = 'usage';
        S.usagePage = 1;
        S.statsPage = 1;
        S.statsTableLoaded = false;
        S.chartData = [];

        var size = calcModalSize();
        S.layerIndex = window._layer.open({
            type: 1,
            title: '数据统计',
            area: [size.w + 'px', size.h + 'px'],
            shadeClose: false,
            maxmin: false,
            content: buildModalHtml(),
            success: function (layero, index) {
                try {
                    var contentEl = layero.find('.layui-layer-content')[0];
                    if (contentEl) {
                        contentEl.style.padding = '0';
                        contentEl.style.overflow = 'hidden';
                    }
                } catch (e) { }
                centerLayer(layero);
                // 窗口缩放 / 旋转（平板横竖屏切换）时，按实际屏幕尺寸动态调整并居中
                S.onResize = function () { resizeModal(layero); };
                window.addEventListener('resize', S.onResize);
                window.addEventListener('orientationchange', S.onResize);
                document.addEventListener('click', onDocClick);
                loadFilters();
                loadUsage();
                loadCharts();
            },
            end: function () {
                S.layerIndex = null;
                if (S.onResize) {
                    window.removeEventListener('resize', S.onResize);
                    window.removeEventListener('orientationchange', S.onResize);
                    S.onResize = null;
                }
                document.removeEventListener('click', onDocClick);
            }
        });
    }

    window.UsageStats = {
        open: openUsageStatsModal,
        switchSubTab: switchSubTab,
        switchMiniTab: switchMiniTab,
        switchLineMetric: switchLineMetric,
        switchBarMetric: switchBarMetric,
        toggleDropdown: toggleDropdown,
        selectOption: selectOption,
        search: search,
        reset: reset
    };
})();
