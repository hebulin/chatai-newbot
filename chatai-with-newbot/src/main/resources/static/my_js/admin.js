function getDialogArea(maxWidth) {
    var w = window.innerWidth;
    if (w <= maxWidth + 20) return ['92%', 'auto'];
    return [maxWidth + 'px', 'auto'];
}

/* Admin JS - Layui Refactored */
var providers = [], allModels = [], allUsers = [];
var allProvidersFull = []; // 厂商管理 Tab 使用：预置 + 自定义
var defaultModelId = null; // 全局默认模型ID（新会话自动选中）
var quickAddProviderId = null;
var filterUsernames = [], filterModelNames = [];
var usagePage = 1, usageSize = 10, statsPage = 1, statsSize = 10;
var _authRedirecting = false; // 防止多个 401 并发时重复弹窗/跳转
var dataSubTab = 'usage';

var providerIconMap = {
    'deepseek': '/icons/deepseek-icon.svg',
    'qwen': '/icons/qwen-icon.svg',
    'kimi': '/icons/kimi-icon.svg',
    'zhipu': '/icons/zhipu-icon.svg',
    'minimax': '/icons/minimax-icon.svg',
    'doubao': '/icons/doubao-icon.svg'
};

function esc(s) { if (!s) return ''; var d = document.createElement('div'); d.textContent = s; return d.innerHTML; }
// 千分位格式化token数量
function fmtToken(n) {
    if (n === undefined || n === null || n === 0) return '0';
    return Number(n).toLocaleString('en-US');
}

function getProviderIconHtml(pid, size) {
    size = size || 24;
    var icon = providerIconMap[pid];
    if (icon) return '<img src="' + icon + '" style="width:' + size + 'px;height:' + size + 'px;object-fit:contain;border-radius:4px;flex-shrink:0;" onerror="this.style.display=\'none\'"/>';
    return '<div style="width:' + size + 'px;height:' + size + 'px;border-radius:4px;background:#334155;display:flex;align-items:center;justify-content:center;color:#94a3b8;font-size:' + Math.round(size*0.5) + 'px;flex-shrink:0;">' + (pid ? pid[0].toUpperCase() : '?') + '</div>';
}
function getProviderSmallIconHtml(pid) { return getProviderIconHtml(pid, 20); }

// 厂商/模型图标：预设厂商及其模型用预设 SVG 图标；自定义厂商及其模型用可设置的 emoji 图标，未设置则显示空占位
function getEntityIconHtml(providerId, icon, size) {
    size = size || 20;
    if (providerId && providerId !== '__custom__') {
        return getProviderIconHtml(providerId, size);
    }
    var ic = icon || '';
    if (ic) {
        return '<span style="font-size:' + size + 'px;line-height:1;display:inline-flex;align-items:center;justify-content:center;width:' + size + 'px;height:' + size + 'px;flex-shrink:0;">' + esc(ic) + '</span>';
    }
    return '<span style="display:inline-block;width:' + size + 'px;height:' + size + 'px;flex-shrink:0;"></span>';
}
function getModelProviderIconHtml(model, size) {
    return getEntityIconHtml(model && model.providerId, model && model.providerIcon, size);
}

function getUsageModelIconHtml(modelName) {
    if (!modelName) return '';
    // 优先按 displayName 匹配当前已配置模型，使用其厂商图标（自定义模型也能显示设置的 emoji 图标）
    var model = null;
    if (allModels && allModels.length) {
        model = allModels.find(function(m) { return (m.displayName || '') === modelName; });
    }
    if (model) {
        return getEntityIconHtml(model.providerId, model.providerIcon, 18);
    }
    // 回退1：按模型名推断预设厂商（显示预设 SVG 图标）
    var pid = inferProviderId(modelName);
    if (pid) {
        return getProviderIconHtml(pid, 18);
    }
    // 回退2：无法识别（如已删除的自定义模型）：显示空占位，不再显示首字母色块
    return '<span style="display:inline-block;width:18px;height:18px;flex-shrink:0;"></span>';
}

function inferProviderId(modelName) {
    if (!modelName) return '';
    var ml = modelName.toLowerCase();
    if (ml.includes('deepseek')) return 'deepseek';
    if (ml.includes('qwen')) return 'qwen';
    if (ml.includes('kimi') || ml.includes('moonshot')) return 'kimi';
    if (ml.includes('glm') || ml.includes('chatglm') || ml.includes('zhipu')) return 'zhipu';
    if (ml.includes('minimax') || ml.includes('abab')) return 'minimax';
    if (ml.includes('doubao')) return 'doubao';
    return '';
}

// Auth check
(function() {
    var token = localStorage.getItem('token');
    if (!token) { window.location.href = '/login.html'; return; }
})();

function headers() {
    return { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + localStorage.getItem('token') };
}
function api(url, opts) {
    opts = opts || {};
    opts.headers = Object.assign({}, headers(), opts.headers || {});
    return fetch(url, opts).then(function(r) {
        if (r.status === 401 || r.status === 403) {
            if (!_authRedirecting) {
                _authRedirecting = true;
                var msg = (r.headers && r.headers.get('X-Auth-Reason') === 'ip_changed') ? '登录IP已变更，请重新登录' : '登录已过期，请重新登录';
                localStorage.removeItem('token');
                if (window._layer && window._layer.msg) {
                    window._layer.msg(msg, { icon: 2, time: 1800 }, function () { window.location.href = '/login.html'; });
                } else {
                    alert(msg);
                    window.location.href = '/login.html';
                }
            }
            throw new Error('Auth failed');
        }
        return r.json();
    });
}

// ===== Layui Init =====
layui.use(['table', 'form', 'layer', 'laypage', 'element', 'jquery'], function() {
    var table = layui.table, form = layui.form, layer = layui.layer;
    var laypage = layui.laypage, element = layui.element, $ = layui.$;
    window._layer = layer; window._$ = $; window._form = form;
    window._laypage = laypage; window._element = element; window._table = table;

    element.on('tab(adminTab)', function(data) {
        var idx = data.index;
        if (idx === 0) { loadProviders(); loadModels(); }
        else if (idx === 1) { loadModels(); }
        else if (idx === 2) { loadProviderMgmt(); }
        else if (idx === 3) { loadUsers(); }
        else if (idx === 4) { loadFilterOptions(); switchDataSubTab(dataSubTab); }
    });

    loadProviders(); loadModels();
});


// ===== Data Loading =====
function loadProviders() {
    api('/api/admin/providers').then(function(data) {
        if (data && data.success) {
            allProvidersFull = data.data || [];
            // 仅预置厂商用于"快速接入"卡片
            providers = allProvidersFull.filter(function(p) { return p.type !== 'custom'; });
            renderProviderGrid();
        }
    });
}
// 厂商管理 Tab 数据加载
function loadProviderMgmt() {
    api('/api/admin/providers').then(function(data) {
        if (data && data.success) {
            allProvidersFull = data.data || [];
            // 同步刷新预置厂商列表（供其他 Tab 使用）
            providers = allProvidersFull.filter(function(p) { return p.type !== 'custom'; });
            applyProviderFilter();
        }
    });
}
function loadModels() {
    api('/api/admin/models').then(function(data) {
        if (data && data.success) {
            allModels = data.data || [];
            defaultModelId = data.defaultModelId || null;
            populateModelFilterOptions();
            applyModelFilter();
            renderProviderGrid();
        }
    });
}
function loadUsers() {
    api('/api/admin/users').then(function(data) {
        if (data && data.success) { allUsers = data.data || []; applyUserFilter(); }
    });
}

// ===== Model Filter =====
function populateModelFilterOptions() {
    var $ = window._$;
    // 厂商下拉：使用模型实际出现的厂商
    var providerMap = {};
    allModels.forEach(function(m) {
        var pid = m.providerId || '__custom__';
        var pname = m.providerName || pid;
        if (!providerMap[pid]) providerMap[pid] = pname;
    });
    // 填充厂商自定义下拉选
    var providerOptions = Object.keys(providerMap).sort().map(function(pid) {
        return { value: pid, text: providerMap[pid] };
    });
    populateCustomSelectOptions('mfProviderArea', 'mfProviderDropdown', 'mfProviderValue', 'mfProvider', providerOptions, '全部厂商', applyModelFilter, function(value, text) {
        // 厂商图标
        return getEntityIconHtml(value, null, 18);
    });

    // 填充模型ID自定义下拉选
    var modelOptions = [];
    var seen = {};
    allModels.forEach(function(m) {
        var mid = m.modelId || '';
        if (mid && !seen[mid]) {
            seen[mid] = true;
            modelOptions.push({ value: mid, text: mid });
        }
    });
    populateCustomSelectOptions('mfModelIdArea', 'mfModelIdDropdown', 'mfModelIdValue', 'mfModelId', modelOptions, '全部模型', applyModelFilter, getUsageModelIconHtml);
}

function applyModelFilter() {
    var $ = window._$;
    var nameKw = ($('#mfName').val() || '').trim().toLowerCase();
    var providerKw = $('#mfProvider').val() || '';
    var modelIdKw = $('#mfModelId').val() || '';
    var thinkKw = $('#mfThinking').val();
    var mmKw = $('#mfMm').val();
    var enabledKw = $('#mfEnabled').val();
    var visKw = $('#mfVisible').val();

    var filtered = allModels.filter(function(m) {
        if (nameKw) {
            var nm = (m.displayName || m.modelId || '').toLowerCase();
            if (nm.indexOf(nameKw) < 0) return false;
        }
        if (providerKw && (m.providerId || '__custom__') !== providerKw) return false;
        if (modelIdKw && m.modelId !== modelIdKw) return false;
        if (thinkKw !== '' && thinkKw !== undefined && thinkKw !== null) {
            var t = m.supportsThinking ? '1' : '0';
            if (t !== thinkKw) return false;
        }
        if (mmKw !== '' && mmKw !== undefined && mmKw !== null) {
            var mm = m.supportsMultimodal ? '1' : '0';
            if (mm !== mmKw) return false;
        }
        if (enabledKw !== '' && enabledKw !== undefined && enabledKw !== null) {
            var en = m.enabled ? '1' : '0';
            if (en !== enabledKw) return false;
        }
        if (visKw !== '' && visKw !== undefined && visKw !== null) {
            var vis = m.visibleToAll ? '1' : '0';
            if (vis !== visKw) return false;
        }
        return true;
    });
    renderModelTable(filtered);
}

function resetModelFilter() {
    var $ = window._$;
    $('#mfName').val('');
    // 重置自定义下拉选
    resetCustomSelect('mfProviderArea', 'mfProviderValue', 'mfProvider', '全部厂商');
    resetCustomSelect('mfModelIdArea', 'mfModelIdValue', 'mfModelId', '全部模型');
    resetCustomSelect('mfThinkingArea', 'mfThinkingValue', 'mfThinking', '全部');
    resetCustomSelect('mfMmArea', 'mfMmValue', 'mfMm', '全部');
    resetCustomSelect('mfEnabledArea', 'mfEnabledValue', 'mfEnabled', '全部');
    resetCustomSelect('mfVisibleArea', 'mfVisibleValue', 'mfVisible', '全部');
    applyModelFilter();
}

// 重置自定义下拉选
function resetCustomSelect(areaId, valueId, hiddenId, defaultText) {
    var $ = window._$;
    var valueSpan = $('#' + valueId);
    var hiddenInput = $('#' + hiddenId);
    var area = $('#' + areaId);
    var dropdown = area.find('.custom-select-dropdown');
    
    valueSpan.text(defaultText);
    hiddenInput.val('');
    dropdown.find('.custom-select-option').removeClass('selected');
    dropdown.find('.custom-select-option[data-value=""]').addClass('selected');
}

// ===== User Filter =====
function applyUserFilter() {
    var $ = window._$;
    var kw = ($('#ufUsername').val() || '').trim().toLowerCase();
    var filtered = allUsers.filter(function(u) {
        if (!kw) return true;
        return (u.username || '').toLowerCase().indexOf(kw) >= 0;
    });
    renderUsersList(filtered);
}

function resetUserFilter() {
    var $ = window._$;
    $('#ufUsername').val('');
    applyUserFilter();
}

// ===== Provider Grid =====
function renderProviderGrid() {
    var $ = window._$, grid = $('#providerGrid');
    grid.empty();
    if (!providers || providers.length === 0) {
        grid.html('<div style="text-align:center;color:#94a3b8;padding:40px;">暂无厂商数据</div>');
        return;
    }
    var html = '';
    providers.forEach(function(p) {
        var pModels = p.models || [];
        var existingCount = allModels.filter(function(m) { return m.providerId === p.id; }).length;
        // totalModels 优先用预设模型列表长度，缺失时回退到后端 modelCount
        var totalModels = pModels.length || p.modelCount || 0;
        var isActive = quickAddProviderId === p.id;
        var allAdded = totalModels > 0 && existingCount >= totalModels;
        html += '<div class="provider-card' + (isActive ? ' active' : '') + '" onclick="onProviderCardClick(\'' + esc(p.id) + '\')">';
        html += '<div class="provider-card-icon">' + getProviderIconHtml(p.id, 40) + '</div>';
        html += '<div class="provider-card-info"><div class="provider-card-name">' + esc(p.name) + '</div>';
        html += '<div class="provider-card-count">已接入 ' + existingCount + ' / ' + totalModels + ' 个模型</div></div>';
        html += '<div class="provider-card-action">';
        if (!allAdded) html += '<button class="provider-card-btn" onclick="event.stopPropagation();showQuickAdd(\'' + esc(p.id) + '\')">一键接入</button>';
        else html += '<span class="provider-card-done">✓ 已全部接入</span>';
        html += '</div></div>';
    });
    grid.html(html);
}
function onProviderCardClick(pid) {
    quickAddProviderId = pid;
    renderProviderGrid();
    renderModelTable(allModels.filter(function(m) { return m.providerId === pid; }));
    if (window._element) window._element.tabChange('adminTab', '1');
}

// ===== Quick Add =====
function showQuickAdd(providerId) {
    var layer = window._layer, form = window._form;
    quickAddProviderId = providerId;
    var provider = providers.find(function(p) { return p.id === providerId; });
    if (!provider) { layer.msg('厂商不存在', {icon: 2}); return; }
    var pModels = provider.models || [], modelCheckboxes = '';
    pModels.forEach(function(pm) {
        var exists = allModels.some(function(m) { return m.providerId === providerId && m.modelId === pm.id; });
        if (!exists) {
            modelCheckboxes += '<div class="model-checkbox-item"><input type="checkbox" name="modelIds" value="' + esc(pm.id) + '" lay-skin="primary" checked>';
            modelCheckboxes += '<label>' + esc(pm.name);
            if (pm.supportsThinking) modelCheckboxes += ' <span class="think-badge">思考</span>';
            if (pm.supportsMultimodal) modelCheckboxes += ' <span class="think-badge" style="background:rgba(99,102,241,.15);color:#818cf8">多模态</span>';
            modelCheckboxes += '</label></div>';
        }
    });
    if (!modelCheckboxes) { layer.msg('该厂商所有模型已接入', {icon: 0}); return; }
    var html = '<div class="layui-form" lay-filter="quickAddForm" style="padding:20px 20px 0;">';
    html += '<div class="form-group"><label class="form-label">厂商</label><div class="form-value">' + getProviderSmallIconHtml(providerId) + esc(provider.name) + '</div></div>';
    html += '<div class="form-group"><label class="form-label">API Key</label><div class="form-value"><input type="text" id="qaApiKey" class="layui-input" placeholder="sk-..." /></div></div>';
    html += '<div class="form-group"><label class="form-label">选择模型</label><div class="form-value model-checkbox-group">' + modelCheckboxes + '</div></div>';
    html += '<div class="form-group"><div class="form-value" style="display:flex;align-items:center;gap:12px;"><span style="min-width:60px;">可见性</span><input type="checkbox" id="qaVisibleToAll" lay-skin="switch" checked></div></div>';
    html += '<div style="text-align:right;padding:10px 0;">';
    html += '<button type="button" class="layui-btn layui-btn-primary" onclick="window._layer.closeAll()">取消</button>';
    html += '<button type="button" class="layui-btn" onclick="submitQuickAdd()">确认接入</button>';
    html += '</div></div>';
    layer.open({ type:1, title:'快速接入 - ' + provider.name, area:getDialogArea(480), content:html,
        success: function(layero) { form.render(null,'quickAddForm'); }
    });
}
function submitQuickAdd() {
    var $ = window._$, layer = window._layer;
    var apiKey = $('#qaApiKey').val().trim();
    if (!apiKey) { layer.msg('请输入 API Key', {icon: 0}); return; }
    var selectedModelIds = [];
    $('input[name=modelIds]:checked').each(function() { selectedModelIds.push($(this).val()); });
    if (selectedModelIds.length === 0) { layer.msg('请至少选择一个模型', {icon: 0}); return; }
    var visibleToAll = $('#qaVisibleToAll').is(':checked');
    api('/api/admin/models/batch', {
        method: 'POST',
        body: JSON.stringify({ providerId: quickAddProviderId, apiKey: apiKey, selectedModelIds: selectedModelIds, visibleToAll: visibleToAll })
    }).then(function(data) {
        if (data && data.success) { layer.closeAll(); layer.msg(data.message || '接入成功', {icon: 1}); loadModels(); }
        else layer.msg(data.message || '接入失败', {icon: 2});
    });
}


// ===== Model Table =====
function renderModelTable(models) {
    var $ = window._$, tbody = $('#modelTableBody');
    tbody.empty();
    if (!models || models.length === 0) {
        tbody.html('<tr><td colspan="8" style="text-align:center;color:#94a3b8;padding:40px;">暂无模型数据</td></tr>');
        return;
    }
    models.forEach(function(m) {
        var statusBtn = m.enabled
            ? '<span class="status-badge status-enabled" onclick="toggleModel(\'' + esc(m.id) + '\',false)">已启用</span>'
            : '<span class="status-badge status-disabled" onclick="toggleModel(\'' + esc(m.id) + '\',true)">已禁用</span>';
        var visBtn = m.visibleToAll
            ? '<span class="status-badge vis-all">所有人</span>'
            : '<span class="status-badge vis-admin">仅管理员</span>';
        var thinkBadge = m.supportsThinking ? '<span class="think-badge">支持</span>' : '<span style="color:#64748b">不支持</span>';
        var mmBadge = m.supportsMultimodal ? '<span class="think-badge" style="background:rgba(99,102,241,.15);color:#818cf8">支持</span>' : '<span style="color:#64748b">不支持</span>';
        var isDefault = (m.id === defaultModelId);
        var defaultBadge = isDefault ? ' <span style="font-size:11px;padding:1px 6px;border-radius:4px;background:rgba(245,158,11,.18);color:#f59e0b;font-weight:500;flex-shrink:0;">默认</span>' : '';
        var defaultBtn = isDefault
            ? '<button class="action-btn" onclick="clearDefaultModel()" title="取消默认" style="color:#f59e0b"><i class="layui-icon layui-icon-star-fill"></i></button>'
            : '<button class="action-btn" onclick="setDefaultModel(\'' + esc(m.id) + '\')" title="设为默认"><i class="layui-icon layui-icon-star"></i></button>';
        var actions = '<div class="action-btns">';
        actions += defaultBtn;
        actions += '<button class="action-btn edit-btn" onclick="editModel(\'' + esc(m.id) + '\')"><i class="layui-icon layui-icon-edit"></i></button>';
        actions += '<button class="action-btn del-btn" onclick="deleteModel(\'' + esc(m.id) + '\')"><i class="layui-icon layui-icon-delete"></i></button>';
        actions += '</div>';
        var tr = '<tr>'
            + '<td><div class="model-name-cell">' + getModelProviderIconHtml(m, 20) + '<span>' + esc(m.displayName || m.modelId) + '</span>' + defaultBadge + '</div></td>'
            + '<td>' + esc(m.providerName || m.providerId) + '</td>'
            + '<td><span class="model-id-text">' + esc(m.modelId) + '</span></td>'
            + '<td>' + thinkBadge + '</td>'
            + '<td>' + mmBadge + '</td>'
            + '<td>' + statusBtn + '</td>'
            + '<td>' + visBtn + '</td>'
            + '<td>' + actions + '</td>'
            + '</tr>';
        tbody.append(tr);
    });
}

function toggleModel(id, enable) {
    var model = allModels.find(function(m) { return m.id === id; });
    if (!model) return;
    api('/api/admin/models/' + id, {
        method: 'PUT',
        body: JSON.stringify(Object.assign({}, model, { enabled: enable }))
    }).then(function(data) {
        if (data && data.success) { window._layer.msg(enable ? '已启用' : '已禁用', {icon:1}); loadModels(); }
        else window._layer.msg(data.message || '操作失败', {icon:2});
    });
}

function isPresetThinkingModel(providerId, modelId) {
    var provider = providers.find(function(p) { return p.id === providerId; });
    if (!provider) return false;
    var pm = (provider.models || []).find(function(m) { return m.id === modelId; });
    return pm && pm.supportsThinking;
}

function isPresetMultimodalModel(providerId, modelId) {
    var provider = providers.find(function(p) { return p.id === providerId; });
    if (!provider) return false;
    var pm = (provider.models || []).find(function(m) { return m.id === modelId; });
    return pm && pm.supportsMultimodal;
}

function editModel(id) {
    var layer = window._layer, form = window._form;
    var model = allModels.find(function(m) { return m.id === id; });
    if (!model) return;
    // 从模型自身数据读取能力（管理员可手动设置）
    var isPresetThinking = model.supportsThinking || false;
    var isPresetMm = model.supportsMultimodal || false;
    var html = '<div class="layui-form" lay-filter="editModelForm" style="padding:20px 20px 0;">';
    html += '<div class="form-group"><label class="form-label">厂商</label><div class="form-value">' + getModelProviderIconHtml(model, 20) + esc(model.providerName || model.providerId) + '</div></div>';
    html += '<div class="form-group"><label class="form-label">显示名</label><div class="form-value"><input type="text" id="emDisplayName" class="layui-input" value="' + esc(model.displayName||'') + '"/></div></div>';
    html += '<div class="form-group"><label class="form-label">模型ID</label><div class="form-value"><input type="text" id="emModelId" class="layui-input" value="' + esc(model.modelId||'') + '" readonly style="opacity:0.6"/></div></div>';
    html += '<div class="form-group"><label class="form-label">API 地址</label><div class="form-value"><input type="text" id="emApiUrl" class="layui-input" value="' + esc(model.apiUrl||'') + '"/></div></div>';
    html += '<div class="form-group"><label class="form-label">API Key</label><div class="form-value"><input type="text" id="emApiKey" class="layui-input" value="' + esc(model.apiKey||'') + '" placeholder="不修改则留空"/></div></div>';
    html += '<div class="form-group"><div class="form-value" style="display:flex;align-items:center;gap:12px;"><span style="min-width:60px;">状态</span><input type="checkbox" id="emEnabled" lay-skin="switch"' + (model.enabled?' checked':'') + '></div></div>';
    html += '<div class="form-group"><div class="form-value" style="display:flex;align-items:center;gap:12px;"><span style="min-width:60px;">可见性</span><input type="checkbox" id="emVisibleToAll" lay-skin="switch"' + (model.visibleToAll?' checked':'') + '></div></div>';
    // 能力开关（可手动设置）
    html += '<div class="form-group"><div class="form-value" style="display:flex;align-items:center;gap:12px;"><span style="min-width:60px;">思考模式</span><input type="checkbox" id="emSupportsThinking" lay-skin="switch"' + (isPresetThinking?' checked':'') + '></div></div>';
    html += '<div class="form-group"><div class="form-value" style="display:flex;align-items:center;gap:12px;"><span style="min-width:60px;">多模态</span><input type="checkbox" id="emSupportsMultimodal" lay-skin="switch"' + (isPresetMm?' checked':'') + '></div></div>';
    html += '<div style="text-align:right;padding:10px 0;">';
    html += '<button type="button" class="layui-btn layui-btn-primary" onclick="window._layer.closeAll()">取消</button>';
    html += '<button type="button" class="layui-btn" onclick="saveModel(\'' + esc(id) + '\')">保存</button>';
    html += '</div></div>';
    layer.open({ type:1, title:'编辑模型 - '+(model.displayName||model.modelId), area:getDialogArea(500), content:html,
        success: function(layero) { form.render(null,'editModelForm'); }
    });
}

function saveModel(id) {
    var $ = window._$, layer = window._layer;
    var model = allModels.find(function(m) { return m.id === id; });
    if (!model) return;
    var payload = Object.assign({}, model, {
        displayName: $('#emDisplayName').val().trim(),
        apiUrl: $('#emApiUrl').val().trim(),
        enabled: $('#emEnabled').is(':checked'),
        visibleToAll: $('#emVisibleToAll').is(':checked'),
        supportsThinking: $('#emSupportsThinking').is(':checked'),
        supportsMultimodal: $('#emSupportsMultimodal').is(':checked')
    });
    var ak = $('#emApiKey').val().trim();
    if (ak) payload.apiKey = ak;
    api('/api/admin/models/' + id, { method:'PUT', body:JSON.stringify(payload) }).then(function(data) {
        if (data && data.success) { layer.closeAll(); layer.msg('保存成功',{icon:1}); loadModels(); }
        else layer.msg(data.message||'保存失败',{icon:2});
    });
}

function deleteModel(id) {
    var model = allModels.find(function(m) { return m.id === id; });
    if (!model) return;
    window._layer.confirm('确定删除模型 "' + (model.displayName||model.modelId) + '" 吗？', {icon:3, title:'确认删除'}, function(idx) {
        api('/api/admin/models/' + id, { method:'DELETE' }).then(function(data) {
            if (data && data.success) { window._layer.close(idx); window._layer.msg('已删除',{icon:1}); loadModels(); }
            else window._layer.msg(data.message||'删除失败',{icon:2});
        });
    });
}

// 设置全局默认模型（全局唯一）
function setDefaultModel(id) {
    api('/api/admin/models/default', { method:'PUT', body: JSON.stringify({ modelId: id }) }).then(function(data) {
        if (data && data.success) { window._layer.msg(data.message || '已设为默认模型', {icon:1}); loadModels(); }
        else window._layer.msg(data.message || '设置失败', {icon:2});
    });
}

// 取消全局默认模型
function clearDefaultModel() {
    api('/api/admin/models/default', { method:'DELETE' }).then(function(data) {
        if (data && data.success) { window._layer.msg(data.message || '已取消默认模型', {icon:1}); loadModels(); }
        else window._layer.msg(data.message || '操作失败', {icon:2});
    });
}


// ===== 厂商管理 =====
function applyProviderFilter() {
    var $ = window._$;
    var nameKw = ($('#pfName').val() || '').trim().toLowerCase();
    var typeKw = $('#pfType').val() || '';
    var filtered = allProvidersFull.filter(function(p) {
        if (typeKw && p.type !== typeKw) return false;
        if (nameKw) {
            var hay = ((p.name || '') + ' ' + (p.id || '')).toLowerCase();
            if (hay.indexOf(nameKw) < 0) return false;
        }
        return true;
    });
    renderProviderTable(filtered);
}

function resetProviderFilter() {
    var $ = window._$;
    $('#pfName').val('');
    resetCustomSelect('pfTypeArea', 'pfTypeValue', 'pfType', '全部');
    applyProviderFilter();
}

function renderProviderTable(providersList) {
    var $ = window._$, tbody = $('#providerTableBody');
    tbody.empty();
    if (!providersList || providersList.length === 0) {
        tbody.html('<tr><td colspan="7" style="text-align:center;color:#94a3b8;padding:40px;">暂无厂商数据</td></tr>');
        return;
    }
    // 按 providerId / 自定义厂商(按 providerName) 统计实际模型数
    var modelCountByProvider = {};
    allModels.forEach(function(m) {
        if (m.providerId === '__custom__') {
            var key = '__custom__::' + (m.providerName || '');
            modelCountByProvider[key] = (modelCountByProvider[key] || 0) + 1;
        } else {
            modelCountByProvider[m.providerId] = (modelCountByProvider[m.providerId] || 0) + 1;
        }
    });
    providersList.forEach(function(p) {
        var isCustom = p.type === 'custom';
        var providerId = p.id || '';
        var modelCount;
        if (isCustom) {
            modelCount = modelCountByProvider['__custom__::' + (p.name || '')] || 0;
        } else {
            modelCount = modelCountByProvider[providerId] || 0;
        }
        var currentName = p.name || '';
        // 预设名称（来自 providers.json）；自定义厂商无原始名称，显示 -
        var presetName = isCustom ? '-' : (p.defaultName || p.name || '');
        // 当前图标：预置厂商来自 providers.json（不可改），自定义厂商来自 ModelConfig.providerIcon
        var currentIcon = p.icon || '';
        var iconCell = currentIcon
            ? '<span style="font-size:18px">' + esc(currentIcon) + '</span>'
            : '<span style="color:#64748b">未设置</span>';
        var typeBadge = isCustom
            ? '<span class="status-badge vis-admin">自定义</span>'
            : '<span class="status-badge status-enabled">预置</span>';
        var encodedId = encodeURIComponent(providerId);
        var actions = '<div class="action-btns">'
            + '<button class="action-btn edit-btn" onclick="showRenameProvider(\'' + encodedId + '\',' + (isCustom ? 'true' : 'false') + ',\'' + esc(currentName).replace(/'/g, "\\'") + '\',\'' + esc(currentIcon).replace(/'/g, "\\'") + '\')" title="修改名称/图标"><i class="layui-icon layui-icon-edit"></i></button>'
            + '</div>';
        var tr = '<tr>'
            + '<td><div class="model-name-cell">' + getEntityIconHtml(providerId, currentIcon, 20) + '<span>' + esc(currentName) + '</span></div></td>'
            + '<td>' + typeBadge + '</td>'
            + '<td><span class="model-id-text">' + esc(providerId) + '</span></td>'
            + '<td>' + modelCount + '</td>'
            + '<td style="color:#94a3b8">' + esc(presetName) + '</td>'
            + '<td style="font-weight:500">' + esc(currentName) + '</td>'
            + '<td>' + iconCell + '</td>'
            + '<td>' + actions + '</td>'
            + '</tr>';
        tbody.append(tr);
    });
}

function showRenameProvider(encodedId, isCustom, currentName, currentIcon) {
    var layer = window._layer, form = window._form;
    var providerId = decodeURIComponent(encodedId);
    var title = isCustom ? '修改自定义厂商' : '修改预置厂商';
    var html = '<div class="layui-form" lay-filter="renameProviderForm" style="padding:20px 20px 0;">';
    html += '<div class="form-group"><label class="form-label">厂商ID</label><div class="form-value"><input type="text" class="layui-input" value="' + esc(providerId) + '" readonly style="opacity:0.6"/></div></div>';
    if (!isCustom) {
        html += '<div class="form-group"><label class="form-label">预设名称（来自 providers.json）</label><div class="form-value" style="color:#94a3b8">' + esc(currentName) + '</div></div>';
    }
    html += '<div class="form-group"><label class="form-label">显示名称</label><div class="form-value"><input type="text" id="rpName" class="layui-input" value="' + esc(currentName) + '" maxlength="100" placeholder="请输入新的显示名"/></div></div>';
    if (isCustom) {
        // 仅自定义厂商支持修改图标（预置厂商的图标来自 providers.json，不可改）
        html += '<div class="form-group"><label class="form-label">模型图标</label><div class="form-value">';
        html += renderIconPicker(currentIcon || '');
        html += '</div></div>';
    } else {
        // 预置厂商：只读展示当前图标（来自 providers.json）
        var readOnlyIconHtml = currentIcon
            ? '<span style="font-size:22px;display:inline-flex;align-items:center;justify-content:center;width:34px;height:34px;border-radius:6px;background:rgba(255,255,255,0.04);border:1px solid rgba(255,255,255,0.08);">' + esc(currentIcon) + '</span>'
            : '<span style="color:#64748b">未设置</span>';
        html += '<div class="form-group"><label class="form-label">模型图标</label><div class="form-value">' + readOnlyIconHtml + '<span style="font-size:12px;color:#64748b;margin-left:8px;">预置厂商的图标不可修改</span></div></div>';
    }
    // 自定义厂商需要保存原名（用于精确定位要修改的模型）
    if (isCustom) {
        html += '<input type="hidden" id="rpOldName" value="' + esc(currentName) + '"/>';
    }
    html += '<div class="form-tip" style="font-size:12px;color:#94a3b8;margin:4px 0 12px;">' + (isCustom ? '修改后将同步更新所有该自定义厂商下的模型（仅匹配当前原名）' : '修改后预置厂商的显示名将立即更新，并同步至所有关联模型（ID/协议/默认URL等不可改）') + '</div>';
    html += '<div style="text-align:right;padding:10px 0;">';
    html += '<button type="button" class="layui-btn layui-btn-primary" onclick="window._layer.closeAll()">取消</button>';
    html += '<button type="button" class="layui-btn" onclick="submitRenameProvider(\'' + encodedId + '\',' + (isCustom ? 'true' : 'false') + ')">保存</button>';
    html += '</div></div>';
    layer.open({ type: 1, title: title, area: getDialogArea(520), content: html,
        success: function(layero) {
            form.render(null, 'renameProviderForm');
            // 绑定图标选择事件（使用 window._$ 避免 $ 未定义）
            var $ = window._$;
            if ($) {
                layero.find('.icon-picker-item').on('click', function() {
                    layero.find('.icon-picker-item').removeClass('selected');
                    $(this).addClass('selected');
                    var v = $(this).attr('data-icon') || '';
                    layero.find('#rpIcon').val(v);
                });
            }
        }
    });
}

// 预设图标列表（12 个简单 emoji）
var PRESET_ICONS = ['🔮','🟣','🌙','🟢','⚡','🫘','⭐','🚀','🤖','💎','🎨','🛠️'];

function renderIconPicker(currentIcon) {
    var html = '<div class="icon-picker">';
    PRESET_ICONS.forEach(function(ic) {
        var selected = (ic === currentIcon) ? ' selected' : '';
        html += '<span class="icon-picker-item' + selected + '" data-icon="' + esc(ic) + '" title="' + esc(ic) + '">' + ic + '</span>';
    });
    html += '<input type="text" id="rpIcon" class="layui-input icon-picker-input" value="' + esc(currentIcon || '') + '" maxlength="4" placeholder="自定义 emoji" />';
    html += '</div>';
    return html;
}

function submitRenameProvider(encodedId, isCustom) {
    var $ = window._$ || window.jQuery, layer = window._layer;
    var providerId = decodeURIComponent(encodedId);
    var newName = $('#rpName').val().trim();
    if (!newName) { layer.msg('请输入新的显示名', {icon: 0}); return; }
    if (newName.length > 100) { layer.msg('名称过长（最多100字符）', {icon: 0}); return; }
    var payload = { name: newName };
    if (isCustom) {
        // 仅自定义厂商支持修改图标
        var newIcon = ($('#rpIcon').val() || '').trim();
        if (newIcon.length > 4) { layer.msg('图标过长（最多4字符）', {icon: 0}); return; }
        payload.icon = newIcon;
        // 自定义厂商需要 oldName 来精确定位（可能有多个不同的自定义名）
        var oldName = $('#rpOldName').val() || '';
        if (!oldName) { layer.msg('原名称丢失，请重新打开弹窗', {icon: 2}); return; }
        payload.oldName = oldName;
    }
    api('/api/admin/providers/' + encodedId, {
        method: 'PATCH',
        body: JSON.stringify(payload)
    }).then(function(data) {
        if (data && data.success) {
            layer.closeAll();
            layer.msg(data.message || '已更新', {icon: 1});
            // 刷新厂商管理 + 模型管理（让模型表里的"厂商"列同步）
            loadProviderMgmt();
            loadModels();
        } else {
            layer.msg(data.message || '更新失败', {icon: 2});
        }
    });
}

function showAddModel() {
    var layer = window._layer, form = window._form, $ = window._$;
    var providerOptions = '';
    providers.forEach(function(p) {
        providerOptions += '<option value="' + esc(p.id) + '">' + esc(p.name) + '</option>';
    });
    providerOptions += '<option value="__custom__">自定义厂商...</option>';
    var html = '<div class="layui-form" lay-filter="addModelForm" style="padding:20px 20px 0;">';
    // 厂商选择
    html += '<div class="form-group"><label class="form-label">厂商</label><div class="form-value"><select id="amProviderId" lay-filter="amProviderId">' + providerOptions + '</select></div></div>';
    // 自定义厂商名称（选择自定义厂商时显示）
    html += '<div id="amCustomProviderFields" style="display:none;">';
    html += '<div class="form-group"><label class="form-label">厂商名称</label><div class="form-value"><input type="text" id="amCustomProviderName" class="layui-input" placeholder="如：OpenAI"/></div></div>';
    html += '</div>';
    // 模型选择（选择内置厂商时显示）
    html += '<div id="amModelSelectGroup" style="display:none;">';
    html += '<div class="form-group"><label class="form-label">模型</label><div class="form-value" id="amModelSelectContainer"></div></div>';
    html += '</div>';
    // 预设模型字段区域（选择内置厂商+预设模型时显示）
    html += '<div id="amPresetFields" style="display:none;">';
    html += '<div class="form-group"><label class="form-label">显示名</label><div class="form-value"><input type="text" id="amPresetDisplayName" class="layui-input" placeholder="显示名称"/></div></div>';
    html += '<div class="form-group"><label class="form-label">模型ID</label><div class="form-value"><input type="text" id="amPresetModelId" class="layui-input" placeholder="模型标识" readonly style="opacity:0.6"/></div></div>';
    html += '<div class="form-group"><label class="form-label">API 地址</label><div class="form-value"><input type="text" id="amPresetApiUrl" class="layui-input" placeholder="API地址"/></div></div>';
    html += '<div class="form-group"><label class="form-label">API Key</label><div class="form-value"><input type="text" id="amPresetApiKey" class="layui-input" placeholder="sk-..."/></div></div>';
    html += '<div class="form-group"><div class="form-value" style="display:flex;align-items:center;gap:12px;"><span style="min-width:60px;">可见性</span><input type="checkbox" id="amPresetVisibleToAll" lay-skin="switch" checked></div></div>';
    html += '<div id="amPresetThinkingInfo"></div>';
    html += '</div>';
    // 自定义模型字段区域（选择内置厂商+自定义模型 或 选择自定义厂商时显示）
    html += '<div id="amCustomFields" style="display:none;">';
    html += '<div class="form-group"><label class="form-label">显示名</label><div class="form-value"><input type="text" id="amDisplayName" class="layui-input" placeholder="可选，默认使用模型ID"/></div></div>';
    html += '<div class="form-group"><label class="form-label">模型ID</label><div class="form-value"><input type="text" id="amModelId" class="layui-input" placeholder="如 gpt-4o"/></div></div>';
    html += '<div class="form-group"><label class="form-label">API 地址</label><div class="form-value"><input type="text" id="amApiUrl" class="layui-input" placeholder="API地址"/></div></div>';
    html += '<div class="form-group"><label class="form-label">API Key</label><div class="form-value"><input type="text" id="amApiKey" class="layui-input" placeholder="sk-..."/></div></div>';
    html += '<div class="form-group"><div class="form-value" style="display:flex;align-items:center;gap:12px;"><span style="min-width:60px;">可见性</span><input type="checkbox" id="amVisibleToAll" lay-skin="switch" checked></div></div>';
    html += '<div class="form-group"><div class="form-value" id="amThinkingValue" style="display:flex;align-items:center;gap:12px;"><span style="min-width:60px;">思考模式</span><input type="checkbox" id="amSupportsThinking" lay-skin="switch"></div></div>';
    html += '<div class="form-group"><div class="form-value" id="amMultimodalValue" style="display:flex;align-items:center;gap:12px;"><span style="min-width:60px;">多模态</span><input type="checkbox" id="amSupportsMultimodal" lay-skin="switch"></div></div>';
    html += '</div>';
    html += '<div style="text-align:right;padding:10px 0;">';
    html += '<button type="button" class="layui-btn layui-btn-primary" onclick="window._layer.closeAll()">取消</button>';
    html += '<button type="button" class="layui-btn" onclick="submitAddModel()">添加</button>';
    html += '</div></div>';
    layer.open({ type:1, title:'添加模型', area:getDialogArea(500), content:html,
        success: function(layero) {
            form.render(null,'addModelForm');
            // 初始化：触发厂商选择
            onAddModelProviderChange();
            // 使用 layui form.on 监听厂商变化
            form.on('select(amProviderId)', function(data) {
                onAddModelProviderChange();
            });
            // 使用 layui form.on 监听模型选择变化
            form.on('select(amModelSelect)', function(data) {
                onAddModelSelectChange(data.value);
            });
        }
    });
}

// 厂商选择变化时的处理
function onAddModelProviderChange() {
    var $ = window._$, form = window._form;
    var providerId = $('#amProviderId').val();
    var isCustomProvider = providerId === '__custom__';
    
    if (isCustomProvider) {
        // 自定义厂商：显示自定义厂商名称输入框和自定义模型字段
        $('#amCustomProviderFields').show();
        $('#amModelSelectGroup').hide();
        $('#amPresetFields').hide();
        $('#amCustomFields').show();
        // 清空自定义厂商的预填URL
        $('#amApiUrl').val('');
        // 重置思考模式/多模态为开关
        $('#amThinkingValue').html('<span style="min-width:60px;">思考模式</span><input type="checkbox" id="amSupportsThinking" lay-skin="switch">');
        $('#amMultimodalValue').html('<span style="min-width:60px;">多模态</span><input type="checkbox" id="amSupportsMultimodal" lay-skin="switch">');
        // 只渲染checkbox，避免重新渲染select导致下拉框损坏
        form.render('checkbox', 'addModelForm');
    } else {
        // 内置厂商：隐藏自定义厂商名称，显示模型选择
        $('#amCustomProviderFields').hide();
        $('#amModelSelectGroup').show();
        // 更新模型下拉列表
        updateAddModelSelect();
    }
}

// 更新模型下拉列表
function updateAddModelSelect() {
    var $ = window._$, form = window._form;
    var providerId = $('#amProviderId').val();
    var provider = providers.find(function(p) { return p.id === providerId; });
    var container = $('#amModelSelectContainer');
    if (!provider) { container.html(''); return; }
    var pModels = provider.models || [];
    var hasPreset = false;
    var selectHtml = '<select id="amModelSelect" lay-filter="amModelSelect">';
    pModels.forEach(function(pm) {
        var exists = allModels.some(function(m) { return m.providerId === providerId && m.modelId === pm.id; });
        var optLabel = esc(pm.name);
        if (pm.supportsThinking) optLabel += ' (思考)';
        if (pm.supportsMultimodal) optLabel += ' (多模态)';
        if (exists) optLabel += ' ✓已接入';
        selectHtml += '<option value="' + esc(pm.id) + '">' + optLabel + '</option>';
        hasPreset = true;
    });
    selectHtml += '<option value="__custom__">自定义模型...</option>';
    selectHtml += '</select>';
    container.html(selectHtml);
    form.render('select', 'addModelForm');
    // 如果没有可用的预设模型，自动选择自定义模式
    if (!hasPreset) {
        $('#amModelSelect').val('__custom__');
        form.render('select', 'addModelForm');
        showAddModelCustomModelFields(provider);
    } else {
        // 默认选择第一个预设模型，显示预设字段
        var firstPresetVal = $('#amModelSelect').val();
        if (firstPresetVal && firstPresetVal !== '__custom__') {
            showAddModelPresetFields(provider, firstPresetVal);
        } else {
            showAddModelCustomModelFields(provider);
        }
    }
}

// 模型选择变化时的处理
function onAddModelSelectChange(modelSelectVal) {
    var $ = window._$;
    var providerId = $('#amProviderId').val();
    var provider = providers.find(function(p) { return p.id === providerId; });
    if (modelSelectVal === '__custom__') {
        showAddModelCustomModelFields(provider);
    } else {
        showAddModelPresetFields(provider, modelSelectVal);
    }
}

// 显示预设模型字段（内置厂商+预设模型）
function showAddModelPresetFields(provider, modelId) {
    var $ = window._$, form = window._form;
    $('#amPresetFields').show();
    $('#amCustomFields').hide();
    // 查找预设模型信息
    var pm = provider ? (provider.models || []).find(function(m) { return m.id === modelId; }) : null;
    // 预填信息
    if (pm) {
        $('#amPresetDisplayName').val(pm.name);
        $('#amPresetModelId').val(pm.id);
        $('#amPresetApiUrl').val(provider ? provider.defaultApiUrl : '');
    }
    // 能力提示：预设模型只标注，不可设置
    var capInfo = $('#amPresetThinkingInfo');
    var capHtml = '<div class="form-group"><label class="form-label">能力</label><div class="form-value" style="display:flex;gap:8px;flex-wrap:wrap;">';
    capHtml += '<span style="font-size:13px;padding:4px 12px;border-radius:6px;' + (pm && pm.supportsThinking ? 'background:rgba(16,185,129,.15);color:#10b981' : 'background:rgba(100,116,139,.1);color:#64748b') + '">思考模式：' + (pm && pm.supportsThinking ? '支持' : '不支持') + '</span>';
    capHtml += '<span style="font-size:13px;padding:4px 12px;border-radius:6px;' + (pm && pm.supportsMultimodal ? 'background:rgba(99,102,241,.15);color:#818cf8' : 'background:rgba(100,116,139,.1);color:#64748b') + '">多模态：' + (pm && pm.supportsMultimodal ? '支持' : '不支持') + '</span>';
    capHtml += '</div></div>';
    capInfo.html(capHtml);
    // 只渲染checkbox，避免重新渲染select导致下拉框损坏
    form.render('checkbox', 'addModelForm');
}

// 显示自定义模型字段（内置厂商+自定义模型 或 自定义厂商）
function showAddModelCustomModelFields(provider) {
    var $ = window._$, form = window._form;
    $('#amPresetFields').hide();
    $('#amCustomFields').show();
    // 预填厂商默认URL
    if (provider) {
        $('#amApiUrl').val(provider.defaultApiUrl || '');
    }
    // 重置思考模式/多模态为开关
    $('#amThinkingValue').html('<span style="min-width:60px;">思考模式</span><input type="checkbox" id="amSupportsThinking" lay-skin="switch">');
    $('#amMultimodalValue').html('<span style="min-width:60px;">多模态</span><input type="checkbox" id="amSupportsMultimodal" lay-skin="switch">');
    // 只渲染checkbox，避免重新渲染select导致下拉框损坏
    form.render('checkbox', 'addModelForm');
}

function submitAddModel() {
    var $ = window._$, layer = window._layer;
    var providerId = $('#amProviderId').val();
    var isCustomProvider = providerId === '__custom__';
    var provider = isCustomProvider ? null : providers.find(function(p) { return p.id === providerId; });
    var payload;

    if (isCustomProvider) {
        // 自定义厂商：所有字段手动填写
        var customProviderName = $('#amCustomProviderName').val().trim();
        var modelId = $('#amModelId').val().trim();
        var apiKey = $('#amApiKey').val().trim();
        if (!customProviderName) { layer.msg('请输入厂商名称', {icon:0}); return; }
        if (!modelId) { layer.msg('请输入模型ID', {icon:0}); return; }
        if (!apiKey) { layer.msg('请输入 API Key', {icon:0}); return; }
        var amThinkingEl = $('#amSupportsThinking');
        var supportsThinking = amThinkingEl.length ? amThinkingEl.is(':checked') : false;
        var amMmEl = $('#amSupportsMultimodal');
        var supportsMultimodal = amMmEl.length ? amMmEl.is(':checked') : false;
        payload = {
            providerId: '__custom__',
            modelId: modelId,
            displayName: $('#amDisplayName').val().trim() || modelId,
            providerName: customProviderName,
            apiUrl: $('#amApiUrl').val().trim(),
            apiKey: apiKey,
            visibleToAll: $('#amVisibleToAll').is(':checked'),
            supportsThinking: supportsThinking,
            supportsMultimodal: supportsMultimodal,
            enabled: true
        };
    } else {
        // 内置厂商
        var modelSelectVal = $('#amModelSelect').val();
        var isCustomModel = modelSelectVal === '__custom__';
        if (isCustomModel) {
            // 内置厂商+自定义模型
            var modelId = $('#amModelId').val().trim();
            var apiKey = $('#amApiKey').val().trim();
            if (!modelId) { layer.msg('请输入模型ID', {icon:0}); return; }
            if (!apiKey) { layer.msg('请输入 API Key', {icon:0}); return; }
            var amThinkingEl = $('#amSupportsThinking');
            var supportsThinking = amThinkingEl.length ? amThinkingEl.is(':checked') : false;
            var amMmEl = $('#amSupportsMultimodal');
            var supportsMultimodal = amMmEl.length ? amMmEl.is(':checked') : false;
            payload = {
                providerId: providerId,
                modelId: modelId,
                displayName: $('#amDisplayName').val().trim() || modelId,
                providerName: provider.name,
                providerIcon: provider.icon,
                apiUrl: $('#amApiUrl').val().trim() || provider.defaultApiUrl,
                apiKey: apiKey,
                visibleToAll: $('#amVisibleToAll').is(':checked'),
                supportsThinking: supportsThinking,
                supportsMultimodal: supportsMultimodal,
                enabled: true
            };
            if (provider) {
                payload.protocol = provider.protocol;
                payload.thinkingParamType = provider.thinkingParamType;
            }
        } else {
            // 内置厂商+预设模型
            var apiKey = $('#amPresetApiKey').val().trim();
            if (!apiKey) { layer.msg('请输入 API Key', {icon:0}); return; }
            var pm = provider ? (provider.models || []).find(function(m) { return m.id === modelSelectVal; }) : null;
            if (!pm) { layer.msg('请选择模型', {icon:0}); return; }
            payload = {
                providerId: providerId,
                modelId: pm.id,
                displayName: $('#amPresetDisplayName').val().trim() || pm.name,
                providerName: provider.name,
                providerIcon: provider.icon,
                apiUrl: $('#amPresetApiUrl').val().trim() || provider.defaultApiUrl,
                apiKey: apiKey,
                visibleToAll: $('#amPresetVisibleToAll').is(':checked'),
                supportsThinking: pm.supportsThinking || false,
                supportsMultimodal: pm.supportsMultimodal || false,
                enabled: true
            };
            if (provider) {
                payload.protocol = provider.protocol;
                payload.thinkingParamType = provider.thinkingParamType;
            }
        }
    }
    api('/api/admin/models', {
        method: 'POST',
        body: JSON.stringify(payload)
    }).then(function(data) {
        if (data && data.success) { layer.closeAll(); layer.msg('添加成功',{icon:1}); loadModels(); }
        else layer.msg(data.message||'添加失败',{icon:2});
    });
}


// ===== Users =====
function renderUsers() {
    renderUsersList(allUsers);
}

function renderUsersList(users) {
    var $ = window._$, tbody = $('#userTableBody');
    tbody.empty();
    if (!users || users.length === 0) {
        tbody.html('<tr><td colspan="7" style="text-align:center;color:#94a3b8;padding:40px;">暂无用户数据</td></tr>');
        return;
    }
    users.forEach(function(u) {
        var roleBadge = u.role === 'admin'
            ? '<span class="status-badge status-enabled">管理员</span>'
            : '<span class="status-badge vis-admin">普通用户</span>';
        var actions = '<div class="action-btns">';
        actions += '<button class="action-btn edit-btn" onclick="editUser(\'' + esc(u.id) + '\')"><i class="layui-icon layui-icon-edit"></i></button>';
        actions += '<button class="action-btn del-btn" onclick="deleteUser(\'' + esc(u.id) + '\')"><i class="layui-icon layui-icon-delete"></i></button>';
        if (u.role !== 'admin') actions += '<button class="action-btn perm-btn" onclick="showPerms(\'' + esc(u.id) + '\')" title="权限"><i class="layui-icon layui-icon-auz"></i></button>';
        actions += '</div>';
        var tr = '<tr>'
            + '<td>' + esc(u.username) + '</td>'
            + '<td>' + roleBadge + '</td>'
            + '<td>' + esc(u.createdAt || '-') + '</td>'
            + '<td>' + esc(u.lastLoginAt || '-') + '</td>'
            + '<td>' + esc(u.lastLoginIp || '-') + '</td>'
            + '<td>' + esc(u.lastLoginBrowser || '-') + '</td>'
            + '<td>' + actions + '</td>'
            + '</tr>';
        tbody.append(tr);
    });
}

function showAddUser() {
    var layer = window._layer, form = window._form;
    var html = '<div class="layui-form" lay-filter="addUserForm" style="padding:20px 20px 0;">';
    html += '<div class="form-group"><label class="form-label">用户名</label><div class="form-value"><input type="text" id="auUsername" class="layui-input" placeholder="请输入用户名"/></div></div>';
    html += '<div class="form-group"><label class="form-label">密码</label><div class="form-value"><input type="password" id="auPassword" class="layui-input" placeholder="请输入密码"/></div></div>';
    html += '<div class="form-group"><label class="form-label">角色</label><div class="form-value"><select id="auRole">';
    html += '<option value="user">普通用户</option><option value="admin">管理员</option>';
    html += '</select></div></div>';
    html += '<div style="text-align:right;padding:10px 0;">';
    html += '<button type="button" class="layui-btn layui-btn-primary" onclick="window._layer.closeAll()">取消</button>';
    html += '<button type="button" class="layui-btn" onclick="submitAddUser()">添加</button>';
    html += '</div></div>';
    layer.open({ type:1, title:'添加用户', area:getDialogArea(420), content:html,
        success: function(layero) { form.render('select','addUserForm'); }
    });
}

function submitAddUser() {
    var $ = window._$, layer = window._layer;
    var username = $('#auUsername').val().trim();
    var password = $('#auPassword').val().trim();
    var role = $('#auRole').val();
    if (!username || !password) { layer.msg('请填写用户名和密码', {icon:0}); return; }
    api('/api/admin/users', {
        method: 'POST',
        body: JSON.stringify({ username:username, password:password, role:role })
    }).then(function(data) {
        if (data && data.success) { layer.closeAll(); layer.msg('添加成功',{icon:1}); loadUsers(); }
        else layer.msg(data.message||'添加失败',{icon:2});
    });
}

function editUser(id) {
    var layer = window._layer, form = window._form;
    var user = allUsers.find(function(u) { return u.id === id; });
    if (!user) return;
    var html = '<div class="layui-form" lay-filter="editUserForm" style="padding:20px 20px 0;">';
    html += '<div class="form-group"><label class="form-label">用户名</label><div class="form-value"><input type="text" id="euUsername" name="euName_' + Date.now() + '" class="layui-input" value="' + esc(user.username) + '" readonly style="opacity:0.6" autocomplete="off" data-lpignore="true" data-form-type="other" /></div></div>';
    html += '<div class="form-group"><label class="form-label">新密码</label><div class="form-value"><input type="password" id="euPassword" class="layui-input" placeholder="不修改则留空"/></div></div>';
    if (user.username === 'admin') {
        html += '<div class="form-group"><label class="form-label">角色</label><div class="form-value" style="color:#818cf8;font-weight:500;">管理员（内置用户不可修改）</div></div>';
    } else {
        html += '<div class="form-group"><label class="form-label">角色</label><div class="form-value"><select id="euRole">';
        html += '<option value="user"' + (user.role==='user'?' selected':'') + '>普通用户</option>';
        html += '<option value="admin"' + (user.role==='admin'?' selected':'') + '>管理员</option>';
        html += '</select></div></div>';
    }
    html += '<div style="text-align:right;padding:10px 0;">';
    html += '<button type="button" class="layui-btn layui-btn-primary" onclick="window._layer.closeAll()">取消</button>';
    html += '<button type="button" class="layui-btn" onclick="saveUser(\'' + esc(id) + '\')">保存</button>';
    html += '</div></div>';
    layer.open({ type:1, title:'编辑用户 - ' + user.username, area:getDialogArea(420), content:html,
        success: function(layero) { if (user.username !== 'admin') form.render('select','editUserForm'); }
    });
}

function saveUser(id) {
    var $ = window._$, layer = window._layer;
    var user = allUsers.find(function(u) { return u.id === id; });
    if (!user) return;
    var payload = { username: user.username };
    var roleEl = $('#euRole');
    if (roleEl.length) {
        payload.role = roleEl.val();
    } else {
        payload.role = user.role;
    }
    var pwd = $('#euPassword').val().trim();
    if (pwd) payload.password = pwd;
    api('/api/admin/users/' + id, { method:'PUT', body:JSON.stringify(payload) }).then(function(data) {
        if (data && data.success) { layer.closeAll(); layer.msg('保存成功',{icon:1}); loadUsers(); }
        else layer.msg(data.message||'保存失败',{icon:2});
    });
}

function deleteUser(id) {
    var user = allUsers.find(function(u) { return u.id === id; });
    if (!user) return;
    window._layer.confirm('确定删除用户 "' + user.username + '" 吗？', {icon:3, title:'确认删除'}, function(idx) {
        api('/api/admin/users/' + id, { method:'DELETE' }).then(function(data) {
            if (data && data.success) { window._layer.close(idx); window._layer.msg('已删除',{icon:1}); loadUsers(); }
            else window._layer.msg(data.message||'删除失败',{icon:2});
        });
    });
}

function showPerms(userId) {
    var layer = window._layer, form = window._form;
    var user = allUsers.find(function(u) { return u.id === userId; });
    if (!user) return;
    var allowedIds = user.allowedModelIds || [];
    var modelCheckboxes = '';
    allModels.forEach(function(m) {
        var checked = allowedIds.indexOf(m.id) >= 0;
        modelCheckboxes += '<div class="model-checkbox-item"><input type="checkbox" name="permModelIds" value="' + m.id + '" lay-skin="primary"' + (checked?' checked':'') + '>';
        modelCheckboxes += '<label>' + getModelProviderIconHtml(m, 18) + esc(m.displayName||m.modelId) + '</label></div>';
    });
    if (!modelCheckboxes) { layer.msg('暂无模型', {icon:0}); return; }
    var html = '<div class="layui-form" lay-filter="permsForm" style="padding:20px 20px 0;">';
    html += '<div class="form-group"><label class="form-label">用户</label><div class="form-value">' + esc(user.username) + '</div></div>';
    html += '<div class="form-group"><label class="form-label">允许的模型</label><div class="form-value model-checkbox-group">' + modelCheckboxes + '</div></div>';
    html += '<div style="text-align:right;padding:10px 0;">';
    html += '<button type="button" class="layui-btn layui-btn-primary" onclick="window._layer.closeAll()">取消</button>';
    html += '<button type="button" class="layui-btn" onclick="savePermissions(\'' + esc(userId) + '\')">保存</button>';
    html += '</div></div>';

    layer.open({ type:1, title:'用户权限 - ' + user.username, area:getDialogArea(520), content:html,
        success: function(layero) { form.render(null,'permsForm'); }
    });
}

function savePermissions(userId) {
    var $ = window._$, layer = window._layer;
    var ids = [];
    $('input[name=permModelIds]:checked').each(function() { ids.push($(this).val()); });
    api('/api/admin/users/' + userId + '/permissions', {
        method: 'PUT',
        body: JSON.stringify({ allowedModelIds: ids })
    }).then(function(data) {
        if (data && data.success) { layer.closeAll(); layer.msg('权限已更新',{icon:1}); loadUsers(); }
        else layer.msg(data.message||'更新失败',{icon:2});
    });
}


// ===== Data Statistics =====
function switchDataSubTab(tab, el) {
    dataSubTab = tab;
    var $ = window._$;
    $('.data-sub-tab-item').removeClass('active');
    $('.data-sub-content').removeClass('active').hide();
    if (tab === 'usage') {
        $(el || '.data-sub-tab-item:first').addClass('active');
        $('#data-sub-usage').addClass('active').show();
        loadUsage();
    } else {
        $(el || '.data-sub-tab-item:last').addClass('active');
        $('#data-sub-stats').addClass('active').show();
        loadUsageStats();
    }
}

function loadFilterOptions() {
    api('/api/admin/usage/filters').then(function(data) {
        if (data && data.success) {
            // API返回 {success, usernames, modelNames} 而非 {success, data: {usernames, modelNames}}
            filterUsernames = data.usernames || [];
            filterModelNames = data.modelNames || [];
            renderUserFilter();
            renderModelFilterDropdown('usageModelSelectArea', 'usageSearchModel', filterModelNames);
            renderModelFilterDropdown('statsModelSelectArea', 'statsSearchModel', filterModelNames);
        }
    });
}

function renderUserFilter() {
    var $ = window._$;
    var userOptions = filterUsernames.map(function(u) {
        return { value: u, text: u };
    });
    // 填充 usageSearchUser 自定义下拉选
    populateCustomSelectOptions('usageSearchUserArea', 'usageSearchUserDropdown', 'usageSearchUserValue', 'usageSearchUser', userOptions, '全部用户');
    // 填充 statsSearchUser 自定义下拉选
    populateCustomSelectOptions('statsSearchUserArea', 'statsSearchUserDropdown', 'statsSearchUserValue', 'statsSearchUser', userOptions, '全部用户');
}

function loadUsage() {
    var $ = window._$;
    var username = $('#usageSearchUser').val() || '';
    var modelName = $('#usageSearchModel').val() || '';
    var date = $('#usageSearchDate').val() || '';
    var url = '/api/admin/usage?page=' + usagePage + '&size=' + usageSize;
    if (username) url += '&username=' + encodeURIComponent(username);
    if (modelName) url += '&modelName=' + encodeURIComponent(modelName);
    if (date) url += '&date=' + encodeURIComponent(date);
    api(url).then(function(data) {
        if (data && data.success) {
            // API返回 {success, data: [...], total, page, size}
            var items = data.data || [];
            var total = data.total || 0;
            renderUsageTable(items);
            renderPagination('usagePagination', total, usagePage, usageSize, function(p) { usagePage = p; loadUsage(); });
        }
    });
}

function loadUsageStats() {
    var $ = window._$;
    var username = $('#statsSearchUser').val() || '';
    var modelName = $('#statsSearchModel').val() || '';
    var date = $('#statsSearchDate').val() || '';
    var url = '/api/admin/usage/stats?page=' + statsPage + '&size=' + statsSize;
    if (username) url += '&username=' + encodeURIComponent(username);
    if (modelName) url += '&modelName=' + encodeURIComponent(modelName);
    if (date) url += '&date=' + encodeURIComponent(date);
    api(url).then(function(data) {
        if (data && data.success) {
            // API返回 {success, data: [...], total, page, size}
            var items = data.data || [];
            var total = data.total || 0;
            renderStatsTable(items);
            renderPagination('statsPagination', total, statsPage, statsSize, function(p) { statsPage = p; loadUsageStats(); });
        }
    });
}

function renderUsageTable(items) {
    var $ = window._$, tbody = $('#usageTableBody');
    tbody.empty();
    if (!items || items.length === 0) {
        tbody.html('<tr><td colspan="8" style="text-align:center;color:#94a3b8;padding:40px;">暂无使用记录</td></tr>');
        return;
    }
    items.forEach(function(r) {
        var tr = '<tr>'
            + '<td>' + esc(r.timestamp || '-') + '</td>'
            + '<td>' + esc(r.username || '-') + '</td>'
            + '<td><div class="model-name-cell">' + getUsageModelIconHtml(r.modelName) + '<span>' + esc(r.modelName || '-') + '</span></div></td>'
            + '<td>' + fmtToken(r.promptTokens) + '</td>'
            + '<td>' + fmtToken(r.completionTokens) + '</td>'
            + '<td>' + fmtToken(r.reasoningTokens) + '</td>'
            + '<td>' + fmtToken(r.cachedTokens) + '</td>'
            + '<td>' + (r.deepThinking ? '<span class="think-badge">思考</span>' : '-') + '</td>'
            + '</tr>';
        tbody.append(tr);
    });
}

function renderStatsTable(items) {
    var $ = window._$, tbody = $('#statsTableBody');
    tbody.empty();
    if (!items || items.length === 0) {
        tbody.html('<tr><td colspan="9" style="text-align:center;color:#94a3b8;padding:40px;">暂无统计数据</td></tr>');
        return;
    }
    items.forEach(function(r) {
        var tr = '<tr>'
            + '<td>' + esc(r.username || '-') + '</td>'
            + '<td>' + esc(r.date || '-') + '</td>'
            + '<td><div class="model-name-cell">' + getUsageModelIconHtml(r.modelName) + '<span>' + esc(r.modelName || '-') + '</span></div></td>'
            + '<td>' + (r.count || 0) + '</td>'
            + '<td>' + fmtToken(r.promptTokens) + '</td>'
            + '<td>' + fmtToken(r.completionTokens) + '</td>'
            + '<td>' + fmtToken(r.reasoningTokens) + '</td>'
            + '<td>' + fmtToken(r.cachedTokens) + '</td>'
            + '<td>' + (r.thinkingCount || 0) + '</td>'
            + '</tr>';
        tbody.append(tr);
    });
}

function renderPagination(elemId, total, current, size, callback) {
    if (!window._laypage) return;
    var isMobile = window.innerWidth <= 480;
    window._laypage.render({ 
        elem: elemId, 
        count: total, 
        limit: size, 
        curr: current, 
        theme: '#6366f1',
        groups: isMobile ? 3 : 5,  // Mobile: show 3 page buttons, Desktop: 5
        layout: isMobile ? ['prev', 'page', 'next'] : ['prev', 'page', 'next', 'count'],
        jump: function(obj, first) { if (!first) callback(obj.curr); }
    });
}

function searchUsage() { usagePage = 1; loadUsage(); }
function searchUsageStats() {
    statsPage = 1;
    loadUsageStats();
    loadUsageStatsCharts();
}

/* ============================================
   用户统计 - 内嵌小tab切换 & 图表渲染
   ============================================ */
var statsChartData = [];

function switchStatsMiniTab(tab, el) {
    var nav = el.parentElement;
    nav.querySelectorAll('.stats-mini-tab-item').forEach(function(n) { n.classList.remove('active'); });
    el.classList.add('active');
    var parent = document.getElementById('data-sub-stats');
    parent.querySelectorAll('.stats-mini-tab-content').forEach(function(c) { c.classList.remove('active'); });
    if (tab === 'list') document.getElementById('stats-mini-list').classList.add('active');
    else if (tab === 'line') {
        document.getElementById('stats-mini-line').classList.add('active');
        renderStatsLineChart();
    } else if (tab === 'bar') {
        document.getElementById('stats-mini-bar').classList.add('active');
        renderStatsBarChart();
    }
}

function loadUsageStatsCharts() {
    var username = $('#statsSearchUser').val() || '';
    var modelName = $('#statsSearchModel').val() || '';
    var date = $('#statsSearchDate').val() || '';
    var url = '/api/admin/usage/stats?page=1&size=500';
    if (username) url += '&username=' + encodeURIComponent(username);
    if (modelName) url += '&modelName=' + encodeURIComponent(modelName);
    if (date) url += '&date=' + encodeURIComponent(date);
    api(url).then(function(data) {
        if (data && data.success) {
            statsChartData = data.data || [];
            // 如果图表视图已激活则重新渲染
            var line = document.getElementById('stats-mini-line');
            var bar = document.getElementById('stats-mini-bar');
            if (line && line.classList.contains('active')) renderStatsLineChart();
            if (bar && bar.classList.contains('active')) renderStatsBarChart();
        }
    });
}

function renderStatsLineChart() {
    var container = document.getElementById('statsLineChart');
    if (!container) return;
    if (!statsChartData || statsChartData.length === 0) {
        container.innerHTML = '<div class="chart-empty-tip">暂无统计数据</div>';
        return;
    }
    // 按日期聚合"调用次数"
    var byDate = {};
    statsChartData.forEach(function(r) {
        var d = r.date || '-';
        byDate[d] = (byDate[d] || 0) + (r.count || 0);
    });
    var dates = Object.keys(byDate).sort();
    var values = dates.map(function(d) { return byDate[d]; });
    container.innerHTML = buildLineChartSvg(dates, values, '调用次数');
}

function renderStatsBarChart() {
    var container = document.getElementById('statsBarChart');
    if (!container) return;
    if (!statsChartData || statsChartData.length === 0) {
        container.innerHTML = '<div class="chart-empty-tip">暂无统计数据</div>';
        return;
    }
    // 按模型聚合"输入Token+输出Token"
    var byModel = {};
    statsChartData.forEach(function(r) {
        var m = r.modelName || '-';
        byModel[m] = (byModel[m] || 0) + (r.promptTokens || 0) + (r.completionTokens || 0);
    });
    var models = Object.keys(byModel).sort(function(a, b) { return byModel[b] - byModel[a]; }).slice(0, 10);
    var values = models.map(function(m) { return byModel[m]; });
    container.innerHTML = buildBarChartSvg(models, values, 'Token总量');
}

function buildLineChartSvg(labels, values, valueName) {
    var w = 720, h = 320;
    var padL = 50, padR = 20, padT = 30, padB = 50;
    var chartW = w - padL - padR;
    var chartH = h - padT - padB;
    var maxV = Math.max.apply(null, values) || 1;
    var niceMax = Math.ceil(maxV * 1.15);
    var points = values.map(function(v, i) {
        var x = padL + (labels.length === 1 ? chartW / 2 : (i / (labels.length - 1)) * chartW);
        var y = padT + chartH - (v / niceMax) * chartH;
        return { x: x, y: y, v: v, label: labels[i] };
    });
    var yTicks = 4;
    var svg = '<svg viewBox="0 0 ' + w + ' ' + h + '" xmlns="http://www.w3.org/2000/svg" preserveAspectRatio="xMidYMid meet">';
    for (var i = 0; i <= yTicks; i++) {
        var yv = niceMax * i / yTicks;
        var yy = padT + chartH - (i / yTicks) * chartH;
        svg += '<line class="chart-axis" x1="' + padL + '" y1="' + yy + '" x2="' + (w - padR) + '" y2="' + yy + '" stroke-opacity="0.15"/>';
        svg += '<text class="chart-label" x="' + (padL - 8) + '" y="' + (yy + 4) + '" text-anchor="end">' + fmtTokenShort(yv) + '</text>';
    }
    // 折线
    if (points.length > 1) {
        var path = 'M ' + points.map(function(p) { return p.x + ' ' + p.y; }).join(' L ');
        svg += '<path d="' + path + '" fill="none" stroke="var(--primary)" stroke-width="2"/>';
        // 填充区域
        var areaPath = path + ' L ' + points[points.length-1].x + ' ' + (padT + chartH) + ' L ' + points[0].x + ' ' + (padT + chartH) + ' Z';
        svg += '<path d="' + areaPath + '" fill="var(--primary)" fill-opacity="0.1" stroke="none"/>';
    }
    // 圆点 + 标签
    points.forEach(function(p) {
        svg += '<circle cx="' + p.x + '" cy="' + p.y + '" r="4" fill="var(--primary)"/>';
        svg += '<text class="chart-value-label" x="' + p.x + '" y="' + (p.y - 10) + '">' + fmtTokenShort(p.v) + '</text>';
    });
    // X轴标签 - 自动旋转避免重叠
    var maxLabel = 8;
    var skipStep = labels.length > maxLabel ? Math.ceil(labels.length / maxLabel) : 1;
    points.forEach(function(p, i) {
        if (i % skipStep !== 0 && i !== points.length - 1) return;
        svg += '<text class="chart-label" x="' + p.x + '" y="' + (padT + chartH + 16) + '" text-anchor="middle">' + esc(p.label.substring(5)) + '</text>';
    });
    svg += '<text class="chart-label" x="' + padL + '" y="' + (h - 10) + '">日期（月-日） · 纵轴：' + valueName + '</text>';
    svg += '</svg>';
    return svg;
}

function buildBarChartSvg(labels, values, valueName) {
    var w = 720, h = 320;
    var padL = 60, padR = 20, padT = 30, padB = 80;
    var chartW = w - padL - padR;
    var chartH = h - padT - padB;
    var maxV = Math.max.apply(null, values) || 1;
    var niceMax = Math.ceil(maxV * 1.15);
    var barW = Math.min(40, chartW / labels.length * 0.6);
    var step = chartW / labels.length;
    var svg = '<svg viewBox="0 0 ' + w + ' ' + h + '" xmlns="http://www.w3.org/2000/svg" preserveAspectRatio="xMidYMid meet">';
    var yTicks = 4;
    for (var i = 0; i <= yTicks; i++) {
        var yv = niceMax * i / yTicks;
        var yy = padT + chartH - (i / yTicks) * chartH;
        svg += '<line class="chart-axis" x1="' + padL + '" y1="' + yy + '" x2="' + (w - padR) + '" y2="' + yy + '" stroke-opacity="0.15"/>';
        svg += '<text class="chart-label" x="' + (padL - 8) + '" y="' + (yy + 4) + '" text-anchor="end">' + fmtTokenShort(yv) + '</text>';
    }
    values.forEach(function(v, i) {
        var x = padL + i * step + (step - barW) / 2;
        var bh = (v / niceMax) * chartH;
        var y = padT + chartH - bh;
        svg += '<rect class="chart-bar" x="' + x + '" y="' + y + '" width="' + barW + '" height="' + bh + '" rx="3"/>';
        svg += '<text class="chart-value-label" x="' + (x + barW / 2) + '" y="' + (y - 4) + '">' + fmtTokenShort(v) + '</text>';
        var lbl = labels[i] || '';
        if (lbl.length > 12) lbl = lbl.substring(0, 11) + '…';
        svg += '<text class="chart-label" x="' + (x + barW / 2) + '" y="' + (padT + chartH + 14) + '" text-anchor="end" transform="rotate(-30 ' + (x + barW / 2) + ' ' + (padT + chartH + 14) + ')">' + esc(lbl) + '</text>';
    });
    svg += '<text class="chart-label" x="' + padL + '" y="' + (h - 8) + '">模型 · 纵轴：' + valueName + '</text>';
    svg += '</svg>';
    return svg;
}

function fmtTokenShort(n) {
    if (n == null) return '0';
    if (n >= 1e6) return (n / 1e6).toFixed(1) + 'M';
    if (n >= 1e3) return (n / 1e3).toFixed(1) + 'K';
    return String(n);
}
function resetUsage() {
    var $ = window._$;
    resetCustomSelect('usageSearchUserArea', 'usageSearchUserValue', 'usageSearchUser', '全部用户');
    $('#usageSearchModel').val('');
    $('#usageSearchDate').val('');
    // 重置自定义下拉框显示文本
    $('#usageModelSelectValue').text('全部模型');
    usagePage = 1;
    loadUsage();
}
function resetUsageStats() {
    var $ = window._$;
    resetCustomSelect('statsSearchUserArea', 'statsSearchUserValue', 'statsSearchUser', '全部用户');
    $('#statsSearchModel').val('');
    $('#statsSearchDate').val('');
    // 重置自定义下拉框显示文本
    $('#statsModelSelectValue').text('全部模型');
    // 重置layui渲染的select
    if (window._form) window._form.render('select');
    statsPage = 1;
    loadUsageStats();
}


// ===== Custom Model Filter Dropdown =====
function renderModelFilterDropdown(areaId, hiddenId, modelNames) {
    var $ = window._$;
    var area = $('#' + areaId);
    if (!area.length) return;
    var trigger = area.find('.custom-select-trigger');
    var dropdown = area.find('.custom-select-dropdown');
    dropdown.empty();
    dropdown.append('<div class="custom-select-option" data-value="" onclick="selectModelFilter(\'' + areaId + '\',\'' + hiddenId + '\',\'\',\'全部模型\')">全部模型</div>');
    modelNames.forEach(function(mn) {
        var iconHtml = getUsageModelIconHtml(mn);
        dropdown.append('<div class="custom-select-option" data-value="' + esc(mn) + '" onclick="selectModelFilter(\'' + areaId + '\',\'' + hiddenId + '\',\'' + esc(mn).replace(/'/g, "\\'") + '\',\'' + esc(mn).replace(/'/g, "\\'") + '\')">' + iconHtml + '<span>' + esc(mn) + '</span></div>');
    });
}

function selectModelFilter(areaId, hiddenId, value, label) {
    var $ = window._$;
    $('#' + hiddenId).val(value);
    var area = $('#' + areaId);
    area.find('.custom-select-value').text(label || '全部模型');
    // 同步更新 selected 状态 - 通过遍历而非选择器
    area.find('.custom-select-option').removeClass('selected');
    area.find('.custom-select-option').each(function() {
        if ($(this).attr('data-value') === value) {
            $(this).addClass('selected');
        }
    });
    area.find('.custom-select-dropdown').removeClass('active');
    area.find('.custom-select-trigger').removeClass('active');
    area.removeClass('active');
}

function toggleCustomDropdown(areaId, e) {
    if (e && typeof e.stopPropagation === 'function') e.stopPropagation();
    var $ = window._$;
    var area = $('#' + areaId);
    if (!area.length) return;
    var dropdown = area.find('.custom-select-dropdown');
    var trigger = area.find('.custom-select-trigger');
    var isOpen = dropdown.hasClass('active');
    // Close all dropdowns first
    $('.custom-select-dropdown').removeClass('active');
    $('.custom-select-trigger').removeClass('active');
    $('.custom-select-area').removeClass('active');
    if (!isOpen) {
        dropdown.addClass('active');
        trigger.addClass('active');
        area.addClass('active'); // 关键：给 area 也添加 active，触发 .custom-select-area.active .custom-select-dropdown { display: block }
    }
}

// 通用自定义下拉选选项选择函数
function selectCustomOption(areaId, dropdownId, valueId, value, text, callback, e) {
    if (e && typeof e.stopPropagation === 'function') e.stopPropagation();
    if (e && typeof e.preventDefault === 'function') e.preventDefault();
    var $ = window._$;
    var area = $('#' + areaId);
    var dropdown = $('#' + dropdownId);
    var valueSpan = $('#' + valueId);
    var hiddenInput = area.siblings('input[type="hidden"]').first();

    // 更新显示文本
    valueSpan.text(text);

    // 更新隐藏 input 的值
    if (hiddenInput.length) {
        hiddenInput.val(value);
    }

    // 更新选中状态 - 通过遍历而非选择器，避免特殊字符转义问题
    dropdown.find('.custom-select-option').removeClass('selected');
    dropdown.find('.custom-select-option').each(function() {
        if ($(this).attr('data-value') === value) {
            $(this).addClass('selected');
        }
    });

    // 关闭下拉 - 同步移除 area、trigger、dropdown 的 active
    area.removeClass('active');
    area.find('.custom-select-trigger').removeClass('active');
    dropdown.removeClass('active');

    // 执行回调
    if (typeof callback === 'function') {
        callback();
    }
}

// 动态填充自定义下拉选选项
function populateCustomSelectOptions(areaId, dropdownId, valueId, hiddenId, options, defaultText, callback, iconFn) {
    var $ = window._$;
    var dropdown = $('#' + dropdownId);
    var valueSpan = $('#' + valueId);
    var hiddenInput = $('#' + hiddenId);
    var cbName = (callback && typeof callback === 'function') ? callback.name : '';

    // 清空现有选项
    dropdown.empty();

    // 添加默认选项
    dropdown.append('<div class="custom-select-option" data-value="" onclick="selectCustomOption(\'' + areaId + '\',\'' + dropdownId + '\',\'' + valueId + '\',\'\',\'' + defaultText + '\',\'' + cbName + '\',event)">' + defaultText + '</div>');

    // 添加其他选项
    options.forEach(function(opt) {
        var iconHtml = (typeof iconFn === 'function') ? iconFn(opt.value, opt.text) : '';
        var inner = iconHtml ? iconHtml + '<span>' + esc(opt.text) + '</span>' : esc(opt.text);
        dropdown.append('<div class="custom-select-option" data-value="' + esc(opt.value) + '" onclick="selectCustomOption(\'' + areaId + '\',\'' + dropdownId + '\',\'' + valueId + '\',\'' + esc(opt.value).replace(/'/g, '&#39;') + '\',\'' + esc(opt.text).replace(/'/g, '&#39;') + '\',\'' + cbName + '\',event)">' + inner + '</div>');
    });

    // 同步初始化：根据当前隐藏 input 的值标记对应项为 selected
    var currentVal = hiddenInput.val() || '';
    var matchOpt = options.find(function(o) { return o.value === currentVal; });
    if (currentVal && matchOpt) {
        valueSpan.text(matchOpt.text);
        dropdown.find('.custom-select-option').removeClass('selected');
        dropdown.find('.custom-select-option[data-value="' + esc(currentVal).replace(/"/g, '\\"') + '"]').addClass('selected');
    } else {
        // 重置为默认值
        valueSpan.text(defaultText);
        hiddenInput.val('');
        dropdown.find('.custom-select-option').removeClass('selected');
        dropdown.find('.custom-select-option[data-value=""]').addClass('selected');
    }
}

// ===== Logout =====
function doLogout() {
    window._layer.confirm('确定退出登录吗？', {icon:3, title:'确认退出'}, function(idx) {
        localStorage.removeItem('token');
        window._layer.close(idx);
        window.location.href = '/login.html';
    });
}

