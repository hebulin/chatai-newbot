/* Admin JS - Layui Refactored */
var providers = [], allModels = [], allUsers = [];
var quickAddProviderId = null;
var filterUsernames = [], filterModelNames = [];
var usagePage = 1, usageSize = 10, statsPage = 1, statsSize = 10;
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
function fmtToken(n) { if (n === undefined || n === null) return '0'; if (n >= 1000000) return (n/1000000).toFixed(1) + 'M'; if (n >= 1000) return (n/1000).toFixed(1) + 'K'; return String(n); }

// 响应式弹窗宽度：移动端自适应，PC端固定最大宽度
function getDialogWidth(maxWidth) {
    maxWidth = maxWidth || 500;
    var viewportWidth = window.innerWidth;
    if (viewportWidth <= 480) return Math.min(viewportWidth - 16, maxWidth);
    if (viewportWidth <= 768) return Math.min(viewportWidth - 32, maxWidth);
    return maxWidth;
}

function getProviderIconHtml(pid, size) {
    size = size || 24;
    var icon = providerIconMap[pid];
    if (icon) return '<img src="' + icon + '" style="width:' + size + 'px;height:' + size + 'px;object-fit:contain;border-radius:4px;flex-shrink:0;" onerror="this.style.display=\'none\'"/>';
    return '<div style="width:' + size + 'px;height:' + size + 'px;border-radius:4px;background:#334155;display:flex;align-items:center;justify-content:center;color:#94a3b8;font-size:' + Math.round(size*0.5) + 'px;flex-shrink:0;">' + (pid ? pid[0].toUpperCase() : '?') + '</div>';
}
function getProviderSmallIconHtml(pid) { return getProviderIconHtml(pid, 20); }

function getUsageModelIconHtml(modelName) {
    if (!modelName) return '';
    var pid = inferProviderId(modelName);
    return getProviderIconHtml(pid, 18);
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

// 安全的localStorage访问封装
function safeStorageGet(key) { try { return localStorage.getItem(key); } catch(e) { return null; } }
function safeStorageSet(key, value) { try { localStorage.setItem(key, value); } catch(e) { console.warn('localStorage不可用:', e.message); } }
function safeStorageRemove(key) { try { localStorage.removeItem(key); } catch(e) {} }

// Auth check
(function() {
    var token = safeStorageGet('token');
    if (!token) { window.location.href = '/login.html'; return; }
})();

function headers() {
    return { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + safeStorageGet('token') };
}
function api(url, opts) {
    opts = opts || {};
    opts.headers = Object.assign({}, headers(), opts.headers || {});
    return fetch(url, opts).then(function(r) {
        if (r.status === 401 || r.status === 403) { safeStorageRemove('token'); window.location.href = '/login.html'; throw new Error('Auth failed'); }
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
        else if (idx === 2) { loadUsers(); }
        else if (idx === 3) { loadFilterOptions(); switchDataSubTab(dataSubTab); }
    });

    loadProviders(); loadModels();
});


// ===== Data Loading =====
function loadProviders() {
    api('/api/admin/providers').then(function(data) {
        if (data && data.success) { providers = data.data || []; renderProviderGrid(); }
    });
}
function loadModels() {
    api('/api/admin/models').then(function(data) {
        if (data && data.success) { allModels = data.data || []; renderModelTable(allModels); renderProviderGrid(); }
    });
}
function loadUsers() {
    api('/api/admin/users').then(function(data) {
        if (data && data.success) { allUsers = data.data || []; renderUsers(); }
    });
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
        var totalModels = pModels.length;
        var isActive = quickAddProviderId === p.id;
        html += '<div class="provider-card' + (isActive ? ' active' : '') + '" onclick="onProviderCardClick(\'' + esc(p.id) + '\')">';
        html += '<div class="provider-card-icon">' + getProviderIconHtml(p.id, 40) + '</div>';
        html += '<div class="provider-card-info"><div class="provider-card-name">' + esc(p.name) + '</div>';
        html += '<div class="provider-card-count">已接入 ' + existingCount + ' / ' + totalModels + ' 个模型</div></div>';
        html += '<div class="provider-card-action">';
        if (existingCount < totalModels) html += '<button class="provider-card-btn" onclick="event.stopPropagation();showQuickAdd(\'' + esc(p.id) + '\')">一键接入</button>';
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
            modelCheckboxes += '<label>' + esc(pm.name) + (pm.supportsThinking ? ' <span class="think-badge">思考</span>' : '') + '</label></div>';
        }
    });
    if (!modelCheckboxes) { layer.msg('该厂商所有模型已接入', {icon: 0}); return; }
    var html = '<div class="layui-form" lay-filter="quickAddForm" style="padding:20px 20px 0;">';
    html += '<div class="form-group"><label class="form-label">厂商</label><div class="form-value">' + getProviderSmallIconHtml(providerId) + esc(provider.name) + '</div></div>';
    html += '<div class="form-group"><label class="form-label">API Key</label><div class="form-value"><input type="text" id="qaApiKey" class="layui-input" placeholder="sk-..." /></div></div>';
    html += '<div class="form-group"><label class="form-label">选择模型</label><div class="form-value model-checkbox-group">' + modelCheckboxes + '</div></div>';
    html += '<div class="form-group"><label class="form-label">可见性</label><div class="form-value"><input type="checkbox" id="qaVisibleToAll" lay-skin="switch" lay-text="所有人|仅管理员" checked></div></div>';
    html += '<div style="text-align:right;padding:10px 0;">';
    html += '<button type="button" class="layui-btn layui-btn-primary" onclick="window._layer.closeAll()">取消</button>';
    html += '<button type="button" class="layui-btn" onclick="submitQuickAdd()">确认接入</button>';
    html += '</div></div>';
    layer.open({ type:1, title:'快速接入 - ' + provider.name, area:[getDialogWidth(480) + 'px','auto'], content:html,
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
        tbody.html('<tr><td colspan="7" style="text-align:center;color:#94a3b8;padding:40px;">暂无模型数据</td></tr>');
        return;
    }
    models.forEach(function(m) {
        var statusBtn = m.enabled
            ? '<span class="status-badge status-enabled" onclick="toggleModel(\'' + esc(m.id) + '\',false)">已启用</span>'
            : '<span class="status-badge status-disabled" onclick="toggleModel(\'' + esc(m.id) + '\',true)">已禁用</span>';
        var visBtn = m.visibleToAll
            ? '<span class="status-badge vis-all">所有人</span>'
            : '<span class="status-badge vis-admin">仅管理员</span>';
        var thinkBadge = m.supportsThinking ? '<span class="think-badge">思考</span>' : '-';
        var actions = '<div class="action-btns">';
        actions += '<button class="action-btn edit-btn" onclick="editModel(\'' + esc(m.id) + '\')"><i class="layui-icon layui-icon-edit"></i></button>';
        actions += '<button class="action-btn del-btn" onclick="deleteModel(\'' + esc(m.id) + '\')"><i class="layui-icon layui-icon-delete"></i></button>';
        actions += '</div>';
        var tr = '<tr>'
            + '<td><div class="model-name-cell">' + getProviderSmallIconHtml(m.providerId) + '<span>' + esc(m.displayName || m.modelId) + '</span></div></td>'
            + '<td>' + esc(m.providerName || m.providerId) + '</td>'
            + '<td><span class="model-id-text">' + esc(m.modelId) + '</span></td>'
            + '<td>' + thinkBadge + '</td>'
            + '<td>' + statusBtn + '</td>'
            + '<td>' + visBtn + '</td>'
            + '<td class="col-sticky-right">' + actions + '</td>'
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

function editModel(id) {
    var layer = window._layer, form = window._form;
    var model = allModels.find(function(m) { return m.id === id; });
    if (!model) return;
    // 判断是否为预设模型（厂商模型列表中存在的模型）
    var provider = providers.find(function(p) { return p.id === model.providerId; });
    var isPresetModel = provider && provider.models && provider.models.some(function(pm) { return pm.id === model.modelId; });
    var html = '<div class="layui-form" lay-filter="editModelForm" style="padding:20px 20px 0;">';
    html += '<div class="form-group"><label class="form-label">厂商</label><div class="form-value">' + getProviderSmallIconHtml(model.providerId) + esc(model.providerName || model.providerId) + '</div></div>';
    html += '<div class="form-group"><label class="form-label">显示名</label><div class="form-value"><input type="text" id="emDisplayName" class="layui-input" value="' + esc(model.displayName||'') + '"/></div></div>';
    html += '<div class="form-group"><label class="form-label">模型ID</label><div class="form-value"><input type="text" id="emModelId" class="layui-input" value="' + esc(model.modelId||'') + '" readonly style="opacity:0.6"/></div></div>';
    html += '<div class="form-group"><label class="form-label">API 地址</label><div class="form-value"><input type="text" id="emApiUrl" class="layui-input" value="' + esc(model.apiUrl||'') + '"/></div></div>';
    html += '<div class="form-group"><label class="form-label">API Key</label><div class="form-value"><input type="text" id="emApiKey" class="layui-input" value="' + esc(model.apiKey||'') + '" placeholder="不修改则留空"/></div></div>';
    html += '<div class="form-group"><label class="form-label">状态</label><div class="form-value"><input type="checkbox" id="emEnabled" lay-skin="switch" lay-text="启用|禁用"' + (model.enabled?' checked':'') + '></div></div>';
    html += '<div class="form-group"><label class="form-label">可见性</label><div class="form-value"><input type="checkbox" id="emVisibleToAll" lay-skin="switch" lay-text="所有人|仅管理员"' + (model.visibleToAll?' checked':'') + '></div></div>';
    if (isPresetModel) {
        // 预设模型：只读显示思考模式信息
        html += '<div class="form-group"><label class="form-label">思考模式</label><div class="form-value">';
        html += model.supportsThinking ? '<span class="think-badge" style="font-size:13px;line-height:20px;">支持深度思考</span>' : '<span style="color:#94a3b8;font-size:14px;">不支持</span>';
        html += '</div></div>';
    } else {
        // 自定义模型：允许用户配置
        html += '<div class="form-group"><label class="form-label">思考模式</label><div class="form-value"><input type="checkbox" id="emSupportsThinking" lay-skin="switch" lay-text="支持|不支持"' + (model.supportsThinking?' checked':'') + '></div></div>';
    }
    html += '<div style="text-align:right;padding:10px 0;">';
    html += '<button type="button" class="layui-btn layui-btn-primary" onclick="window._layer.closeAll()">取消</button>';
    html += '<button type="button" class="layui-btn" onclick="saveModel(\'' + esc(id) + '\')">保存</button>';
    html += '</div></div>';
    layer.open({ type:1, title:'编辑模型 - '+(model.displayName||model.modelId), area:[getDialogWidth(500) + 'px','auto'], content:html,
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
        supportsThinking: $('#emSupportsThinking').is(':checked')
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

// 添加模型时的当前厂商ID（用于模型下拉选回调）
var addModelCurrentProviderId = '';

function showAddModel() {
    var layer = window._layer, form = window._form;
    addModelCurrentProviderId = '';
    var providerOptions = '<option value="">请选择厂商</option>';
    providers.forEach(function(p) {
        providerOptions += '<option value="' + esc(p.id) + '">' + esc(p.name) + '</option>';
    });
    providerOptions += '<option value="__custom__">自定义</option>';
    var html = '<div class="layui-form" lay-filter="addModelForm" style="padding:20px 20px 0;">';
    html += '<div class="form-group"><label class="form-label">厂商</label><div class="form-value"><select id="amProviderId" lay-filter="amProviderId">' + providerOptions + '</select></div></div>';
    html += '<div class="form-group" id="amModelIdGroup"><label class="form-label">模型ID</label><div class="form-value" id="amModelIdValue"><input type="text" id="amModelId" class="layui-input" placeholder="请先选择厂商"/></div></div>';
    html += '<div class="form-group" id="amApiUrlGroup" style="display:none;"><label class="form-label">API 地址</label><div class="form-value"><input type="text" id="amApiUrl" class="layui-input" placeholder="如 https://api.example.com/v1"/></div></div>';
    html += '<div class="form-group"><label class="form-label">API Key</label><div class="form-value"><input type="text" id="amApiKey" class="layui-input" placeholder="sk-..."/></div></div>';
    html += '<div class="form-group"><label class="form-label">可见性</label><div class="form-value"><input type="checkbox" id="amVisibleToAll" lay-skin="switch" lay-text="所有人|仅管理员" checked></div></div>';
    html += '<div class="form-group" id="amThinkingGroup" style="display:none;"><label class="form-label">思考模式</label><div class="form-value" id="amThinkingValue"></div></div>';
    html += '<div style="text-align:right;padding:10px 0;">';
    html += '<button type="button" class="layui-btn layui-btn-primary" onclick="window._layer.closeAll()">取消</button>';
    html += '<button type="button" class="layui-btn" onclick="submitAddModel()">添加</button>';
    html += '</div></div>';
    layer.open({ type:1, title:'添加模型', area:[getDialogWidth(500) + 'px','auto'], content:html,
        success: function(layero) {
            form.render(null,'addModelForm');
            form.on('select(amProviderId)', function(data) {
                onAddModelProviderChange(data.value);
            });
            form.on('select(amModelId)', function(data) {
                onAddModelModelChange(addModelCurrentProviderId, data.value);
            });
        }
    });
}

function onAddModelProviderChange(providerId) {
    var form = window._form, $ = window._$;
    var $modelIdValue = $('#amModelIdValue');
    var $apiUrlGroup = $('#amApiUrlGroup');
    var $thinkingGroup = $('#amThinkingGroup');
    var $thinkingValue = $('#amThinkingValue');

    // 更新当前厂商ID
    addModelCurrentProviderId = providerId;

    if (!providerId) {
        // 未选择厂商
        $modelIdValue.html('<input type="text" id="amModelId" class="layui-input" placeholder="请先选择厂商"/>');
        $apiUrlGroup.hide();
        $thinkingGroup.hide();
        return;
    }

    if (providerId === '__custom__') {
        // 自定义厂商：所有字段需要手动配置
        $modelIdValue.html('<input type="text" id="amModelId" class="layui-input" placeholder="如 deepseek-chat"/>');
        $apiUrlGroup.show();
        $thinkingGroup.show();
        $thinkingValue.html('<input type="checkbox" id="amSupportsThinking" lay-skin="switch" lay-text="支持|不支持">');
        form.render(null, 'addModelForm');
        return;
    }

    // 预设厂商：模型ID变为下拉选
    var provider = providers.find(function(p) { return p.id === providerId; });
    if (!provider) return;

    var modelOptions = '<option value="">请选择模型</option>';
    (provider.models || []).forEach(function(pm) {
        var exists = allModels.some(function(m) { return m.providerId === providerId && m.modelId === pm.id; });
        if (!exists) {
            modelOptions += '<option value="' + esc(pm.id) + '">' + esc(pm.name) + (pm.supportsThinking ? ' (支持思考)' : '') + '</option>';
        }
    });
    modelOptions += '<option value="__custom__">自定义</option>';

    $modelIdValue.html('<select id="amModelId" lay-filter="amModelId">' + modelOptions + '</select>');
    $apiUrlGroup.hide();
    $thinkingGroup.hide();
    form.render('select', 'addModelForm');
}

function onAddModelModelChange(providerId, modelId) {
    var form = window._form, $ = window._$;
    var $modelIdValue = $('#amModelIdValue');
    var $apiUrlGroup = $('#amApiUrlGroup');
    var $thinkingGroup = $('#amThinkingGroup');
    var $thinkingValue = $('#amThinkingValue');

    if (!modelId) {
        $apiUrlGroup.hide();
        $thinkingGroup.hide();
        return;
    }

    if (modelId === '__custom__') {
        // 自定义模型：切换为input输入框，需要用户配置
        $modelIdValue.html('<input type="text" id="amModelId" class="layui-input" placeholder="请输入自定义模型ID"/>');
        var provider = providers.find(function(p) { return p.id === providerId; });
        if (provider) {
            $apiUrlGroup.hide();
        } else {
            $apiUrlGroup.show();
        }
        $thinkingGroup.show();
        $thinkingValue.html('<input type="checkbox" id="amSupportsThinking" lay-skin="switch" lay-text="支持|不支持">');
        form.render(null, 'addModelForm');
        return;
    }

    // 预设模型：只读显示思考模式信息
    var provider = providers.find(function(p) { return p.id === providerId; });
    var pm = provider && provider.models && provider.models.find(function(m) { return m.id === modelId; });

    $apiUrlGroup.hide();
    $thinkingGroup.show();
    if (pm && pm.supportsThinking) {
        $thinkingValue.html('<span class="think-badge" style="font-size:13px;line-height:20px;">支持深度思考</span>');
    } else {
        $thinkingValue.html('<span style="color:#94a3b8;font-size:14px;">不支持</span>');
    }
}

function submitAddModel() {
    var $ = window._$, layer = window._layer;
    var providerId = $('#amProviderId').val();
    var modelIdEl = $('#amModelId');
    var modelId = modelIdEl.val();
    if (modelIdEl.is('input')) modelId = modelId.trim();
    var apiKey = $('#amApiKey').val().trim();

    if (!providerId) { layer.msg('请选择厂商', {icon:0}); return; }
    if (!modelId) { layer.msg('请选择或输入模型ID', {icon:0}); return; }
    if (!apiKey) { layer.msg('请输入 API Key', {icon:0}); return; }

    var isCustomProvider = providerId === '__custom__';
    // 通过元素类型判断：input=自定义模型，select=预设模型
    var isModelInput = modelIdEl.is('input');

    if (isCustomProvider) {
        // 自定义厂商：需要用户输入所有信息
        var customApiUrl = $('#amApiUrl').val().trim();
        if (!customApiUrl) { layer.msg('自定义厂商需要填写API地址', {icon:0}); return; }
        var payload = {
            providerId: '__custom__',
            providerName: '自定义',
            modelId: modelId,
            displayName: modelId,
            apiUrl: customApiUrl,
            apiKey: apiKey,
            visibleToAll: $('#amVisibleToAll').is(':checked'),
            supportsThinking: $('#amSupportsThinking').is(':checked'),
            enabled: true
        };
        api('/api/admin/models', {
            method: 'POST',
            body: JSON.stringify(payload)
        }).then(function(data) {
            if (data && data.success) { layer.closeAll(); layer.msg('添加成功',{icon:1}); loadModels(); }
            else layer.msg(data.message||'添加失败',{icon:2});
        });
        return;
    }

    // 预设厂商
    var provider = providers.find(function(p) { return p.id === providerId; });

    if (isModelInput) {
        // 预设厂商 + 自定义模型：需要用户配置
        var payload = {
            providerId: providerId,
            providerName: provider ? provider.name : providerId,
            modelId: modelId,
            displayName: modelId,
            apiUrl: provider ? provider.apiUrl : '',
            apiKey: apiKey,
            visibleToAll: $('#amVisibleToAll').is(':checked'),
            supportsThinking: $('#amSupportsThinking').is(':checked'),
            enabled: true
        };
        api('/api/admin/models', {
            method: 'POST',
            body: JSON.stringify(payload)
        }).then(function(data) {
            if (data && data.success) { layer.closeAll(); layer.msg('添加成功',{icon:1}); loadModels(); }
            else layer.msg(data.message||'添加失败',{icon:2});
        });
        return;
    }

    // 预设厂商 + 预设模型：自动获取配置
    var pm = provider && provider.models && provider.models.find(function(m) { return m.id === modelId; });
    var payload = {
        providerId: providerId,
        providerName: provider ? provider.name : providerId,
        modelId: modelId,
        displayName: pm ? pm.name : modelId,
        apiUrl: provider ? provider.apiUrl : '',
        apiKey: apiKey,
        visibleToAll: $('#amVisibleToAll').is(':checked'),
        supportsThinking: pm ? !!pm.supportsThinking : false,
        enabled: true
    };
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
    var $ = window._$, tbody = $('#userTableBody');
    tbody.empty();
    if (!allUsers || allUsers.length === 0) {
        tbody.html('<tr><td colspan="7" style="text-align:center;color:#94a3b8;padding:40px;">暂无用户数据</td></tr>');
        return;
    }
    allUsers.forEach(function(u) {
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
            + '<td class="col-sticky-right">' + actions + '</td>'
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
    layer.open({ type:1, title:'添加用户', area:[getDialogWidth(420) + 'px','auto'], content:html,
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
    html += '<div class="form-group"><label class="form-label">用户名</label><div class="form-value"><input type="text" id="euUsername" class="layui-input" value="' + esc(user.username) + '" readonly style="opacity:0.6"/></div></div>';
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
    layer.open({ type:1, title:'编辑用户 - ' + user.username, area:[getDialogWidth(420) + 'px','auto'], content:html,
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
        modelCheckboxes += '<label>' + getProviderSmallIconHtml(m.providerId) + esc(m.displayName||m.modelId) + '</label></div>';
    });
    if (!modelCheckboxes) { layer.msg('暂无模型', {icon:0}); return; }
    var html = '<div class="layui-form" lay-filter="permsForm" style="padding:20px 20px 0;">';
    html += '<div class="form-group"><label class="form-label">用户</label><div class="form-value">' + esc(user.username) + '</div></div>';
    html += '<div class="form-group"><label class="form-label">允许的模型</label><div class="form-value model-checkbox-group">' + modelCheckboxes + '</div></div>';
    html += '<div style="text-align:right;padding:10px 0;">';
    html += '<button type="button" class="layui-btn layui-btn-primary" onclick="window._layer.closeAll()">取消</button>';
    html += '<button type="button" class="layui-btn" onclick="savePermissions(\'' + esc(userId) + '\')">保存</button>';
    html += '</div></div>';

    layer.open({ type:1, title:'用户权限 - ' + user.username, area:[getDialogWidth(520) + 'px','auto'], content:html,
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
    $('.sub-tab-card').removeClass('active');
    $('.data-sub-content').removeClass('active').hide();
    if (tab === 'usage') {
        $(el || '.sub-tab-card:first').addClass('active');
        $('#data-sub-usage').addClass('active').show();
        loadUsage();
    } else {
        $(el || '.sub-tab-card:last').addClass('active');
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
    ['usageSearchUser', 'statsSearchUser'].forEach(function(id) {
        var sel = $('#' + id);
        sel.empty();
        sel.append('<option value="">全部用户</option>');
        filterUsernames.forEach(function(u) { sel.append('<option value="' + esc(u) + '">' + esc(u) + '</option>'); });
    });
    if (window._form) window._form.render('select');
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
        tbody.html('<tr><td colspan="7" style="text-align:center;color:#94a3b8;padding:40px;">暂无使用记录</td></tr>');
        return;
    }
    items.forEach(function(r) {
        var tr = '<tr>'
            + '<td>' + esc(r.timestamp || '-') + '</td>'
            + '<td>' + esc(r.username || '-') + '</td>'
            + '<td><div class="model-name-cell">' + getUsageModelIconHtml(r.modelName) + '<span>' + esc(r.modelName || '-') + '</span></div></td>'
            + '<td>' + fmtToken(r.promptTokens) + '</td>'
            + '<td>' + fmtToken(r.completionTokens) + '</td>'
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
        tbody.html('<tr><td colspan="8" style="text-align:center;color:#94a3b8;padding:40px;">暂无统计数据</td></tr>');
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
            + '<td>' + fmtToken(r.cachedTokens) + '</td>'
            + '<td>' + (r.thinkingCount || 0) + '</td>'
            + '</tr>';
        tbody.append(tr);
    });
}

function renderPagination(elemId, total, current, size, callback) {
    if (!window._laypage) return;
    window._laypage.render({ elem:elemId, count:total, limit:size, curr:current, theme:'#6366f1',
        layout:['prev','page','next','count'],
        jump: function(obj, first) { if (!first) callback(obj.curr); }
    });
}

function searchUsage() { usagePage = 1; loadUsage(); }
function searchUsageStats() { statsPage = 1; loadUsageStats(); }
function resetUsage() {
    var $ = window._$;
    $('#usageSearchUser').val('');
    $('#usageSearchModel').val('');
    $('#usageSearchDate').val('');
    // 重置自定义下拉框显示文本
    $('#usageModelSelectValue').text('全部模型');
    // 重置layui渲染的select
    if (window._form) window._form.render('select');
    usagePage = 1;
    loadUsage();
}
function resetUsageStats() {
    var $ = window._$;
    $('#statsSearchUser').val('');
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
    area.find('.custom-select-dropdown').removeClass('active');
    area.find('.custom-select-trigger').removeClass('active');
}

function toggleCustomDropdown(areaId) {
    var $ = window._$;
    var area = $('#' + areaId);
    var dropdown = area.find('.custom-select-dropdown');
    var trigger = area.find('.custom-select-trigger');
    var isOpen = dropdown.hasClass('active');
    // Close all dropdowns first
    $('.custom-select-dropdown').removeClass('active');
    $('.custom-select-trigger').removeClass('active');
    if (!isOpen) {
        dropdown.addClass('active');
        trigger.addClass('active');
    }
}

// ===== Logout =====
function doLogout() {
    window._layer.confirm('确定退出登录吗？', {icon:3, title:'确认退出'}, function(idx) {
        safeStorageRemove('token');
        window._layer.close(idx);
        window.location.href = '/login.html';
    });
}
