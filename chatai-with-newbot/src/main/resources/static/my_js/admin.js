var TOKEN = localStorage.getItem('token');
var providers = [];
var allModels = [];
var allUsers = [];
var editUserId = null;
var quickAddProviderId = null;

// ===== Custom UI: Toast / Alert / Confirm =====
function showToast(msg, type) {
    type = type || 'info';
    var container = document.getElementById('toastContainer');
    var el = document.createElement('div');
    el.className = 'toast-item ' + type;
    var icons = { success: '✓', error: '✕', warning: '⚠', info: 'ℹ' };
    el.innerHTML = '<span style="font-size:16px">' + (icons[type] || '') + '</span><span>' + esc(msg) + '</span>';
    container.appendChild(el);
    setTimeout(function() {
        el.style.animation = 'toastOut .3s ease forwards';
        setTimeout(function() { if (el.parentNode) el.parentNode.removeChild(el); }, 300);
    }, 3000);
}

function showAlert(msg, title, onOk) {
    title = title || '提示';
    var overlay = document.getElementById('customOverlay');
    document.getElementById('customDialogIcon').innerHTML = '<svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="#6366f1" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>';
    document.getElementById('customDialogTitle').textContent = title;
    document.getElementById('customDialogMsg').textContent = msg;
    var actions = document.getElementById('customDialogActions');
    actions.innerHTML = '';
    var btn = document.createElement('button');
    btn.className = 'dialog-confirm';
    btn.textContent = '确定';
    btn.onclick = function() { hideCustomDialog(); if (onOk) onOk(); };
    actions.appendChild(btn);
    overlay.classList.add('active');
}

function showConfirm(msg, title, onOk) {
    title = title || '确认';
    var overlay = document.getElementById('customOverlay');
    document.getElementById('customDialogIcon').innerHTML = '<svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="#f59e0b" stroke-width="2"><path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>';
    document.getElementById('customDialogTitle').textContent = title;
    document.getElementById('customDialogMsg').textContent = msg;
    var actions = document.getElementById('customDialogActions');
    actions.innerHTML = '';
    var cancelBtn = document.createElement('button');
    cancelBtn.className = 'dialog-cancel';
    cancelBtn.textContent = '取消';
    cancelBtn.onclick = hideCustomDialog;
    var okBtn = document.createElement('button');
    okBtn.className = 'dialog-confirm';
    okBtn.textContent = '确定';
    okBtn.onclick = function() { hideCustomDialog(); if (onOk) onOk(); };
    actions.appendChild(cancelBtn);
    actions.appendChild(okBtn);
    overlay.classList.add('active');
}

function showDangerConfirm(msg, title, onOk) {
    title = title || '确认删除';
    var overlay = document.getElementById('customOverlay');
    document.getElementById('customDialogIcon').innerHTML = '<svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>';
    document.getElementById('customDialogTitle').textContent = title;
    document.getElementById('customDialogMsg').textContent = msg;
    var actions = document.getElementById('customDialogActions');
    actions.innerHTML = '';
    var cancelBtn = document.createElement('button');
    cancelBtn.className = 'dialog-cancel';
    cancelBtn.textContent = '取消';
    cancelBtn.onclick = hideCustomDialog;
    var okBtn = document.createElement('button');
    okBtn.className = 'dialog-danger';
    okBtn.textContent = '删除';
    okBtn.onclick = function() { hideCustomDialog(); if (onOk) onOk(); };
    actions.appendChild(cancelBtn);
    actions.appendChild(okBtn);
    overlay.classList.add('active');
}

function hideCustomDialog() {
    document.getElementById('customOverlay').classList.remove('active');
}

if (!TOKEN || localStorage.getItem('role') !== 'admin') {
    localStorage.removeItem('token');
    localStorage.removeItem('username');
    localStorage.removeItem('role');
    window.location.href = '/login.html';
}

function headers() { return { 'Authorization': 'Bearer ' + TOKEN, 'Content-Type': 'application/json' }; }

function api(url, opts) {
    opts = opts || {};
    opts.headers = headers();
    return fetch(url, opts).then(function(r) {
        if (r.status === 401) {
            localStorage.removeItem('token');
            localStorage.removeItem('username');
            localStorage.removeItem('role');
            showAlert('登录已过期，请重新登录', '会话过期', function() { window.location.href = '/login.html'; });
            return;
        }
        if (r.status === 403) { showToast('无权限', 'error'); return; }
        return r.json();
    });
}

// ===== Init =====
(function() {
    loadProviders();
    loadModels();
    loadUsers();
    loadFilterOptions();
})();

function switchSection(name) {
    document.querySelectorAll('.section').forEach(function(s) { s.classList.remove('active'); });
    document.querySelectorAll('.nav-tab').forEach(function(t) { t.classList.remove('active'); });
    var sec = document.getElementById('sec-' + name);
    if (sec) sec.classList.add('active');
    // 找到对应的 nav-tab 按钮并设为 active
    document.querySelectorAll('.nav-tab').forEach(function(t) {
        if (t.getAttribute('onclick') && t.getAttribute('onclick').indexOf("'" + name + "'") !== -1) {
            t.classList.add('active');
        }
    });
    // 切换到数据统计时，自动加载当前子标签的数据
    if (name === 'data') {
        loadFilterOptions();
        var activeSub = document.querySelector('.sub-menu-item.active');
        if (activeSub) {
            var subName = activeSub.getAttribute('onclick').match(/'(\w+)'/);
            if (subName) {
                if (subName[1] === 'usage') loadUsage(1);
                else if (subName[1] === 'stats') loadUsageStats(1);
            }
        }
    }
}

// ===== Quick Add (快速接入) =====
function renderProviderGrid() {
    var grid = document.getElementById('providerGrid');
    if (!grid) return;
    grid.innerHTML = '';
    providers.forEach(function(p) {
        // 统计已接入模型数
        var addedCount = 0;
        allModels.forEach(function(m) {
            if (m.providerId === p.id) addedCount++;
        });
        var totalModels = p.models ? p.models.length : 0;
        var thinkingCount = p.models ? p.models.filter(function(m) { return m.supportsThinking; }).length : 0;

        var card = document.createElement('div');
        card.className = 'provider-card' + (addedCount > 0 ? ' has-models' : '');
        card.innerHTML =
            '<div class="provider-card-header">' +
                '<span class="provider-icon">' + (p.icon || '📦') + '</span>' +
                '<div class="provider-info">' +
                    '<h3>' + esc(p.name) + '</h3>' +
                    '<span class="provider-status">' + (addedCount > 0 ? '已接入 ' + addedCount + '/' + totalModels + ' 个模型' : '未接入') + '</span>' +
                '</div>' +
            '</div>' +
            '<div class="provider-card-meta">' +
                '<span class="meta-item">' + totalModels + ' 个模型</span>' +
                (thinkingCount > 0 ? '<span class="meta-item think">🧠 ' + thinkingCount + ' 个支持思考</span>' : '') +
            '</div>' +
            '<div class="provider-card-models">' +
                (p.models || []).map(function(m) {
                    var isAdded = allModels.some(function(am) { return am.providerId === p.id && am.modelId === m.id; });
                    return '<span class="model-tag' + (isAdded ? ' added' : '') + (m.supportsThinking ? ' thinking' : '') + '">' +
                        esc(m.name) + (isAdded ? ' ✓' : '') + '</span>';
                }).join('') +
            '</div>' +
            '<button class="primary-btn provider-quick-btn" onclick="showQuickAdd(\'' + p.id + '\')">' +
                (addedCount > 0 ? '管理接入' : '一键接入') +
            '</button>';
        grid.appendChild(card);
    });
}

function showQuickAdd(providerId) {
    quickAddProviderId = providerId;
    var provider = providers.find(function(p) { return p.id === providerId; });
    if (!provider) return;

    document.getElementById('quickAddTitle').textContent = '快速接入 - ' + provider.name;
    document.getElementById('quickAddProviderInfo').innerHTML =
        '<span class="provider-icon-lg">' + (provider.icon || '📦') + '</span>' +
        '<div><strong>' + esc(provider.name) + '</strong><br>' +
        '<small>API: ' + esc(provider.defaultApiUrl) + '</small></div>';

    // 生成模型复选框列表
    var listEl = document.getElementById('qaModelList');
    listEl.innerHTML = '';
    (provider.models || []).forEach(function(m) {
        var isAdded = allModels.some(function(am) { return am.providerId === providerId && am.modelId === m.id; });
        var item = document.createElement('div');
        item.className = 'model-check-item' + (isAdded ? ' already-added' : '');
        item.innerHTML =
            '<label>' +
                '<input type="checkbox" value="' + esc(m.id) + '"' + (isAdded ? ' disabled' : ' checked') + '>' +
                '<span class="model-check-name">' + esc(m.name) + '</span>' +
                (m.supportsThinking ? '<span class="thinking-mini-badge">思考</span>' : '') +
                (isAdded ? '<span class="added-tag">已接入</span>' : '') +
            '</label>';
        listEl.appendChild(item);
    });

    document.getElementById('qaApiKey').value = '';
    document.getElementById('qaSelectAll').checked = true;
    document.getElementById('qaVisible').checked = true;
    document.getElementById('quickAddModal').classList.add('active');
}

function closeQuickAddModal() {
    document.getElementById('quickAddModal').classList.remove('active');
    quickAddProviderId = null;
}

function toggleSelectAllModels() {
    var checked = document.getElementById('qaSelectAll').checked;
    document.querySelectorAll('#qaModelList input[type="checkbox"]:not(:disabled)').forEach(function(cb) {
        cb.checked = checked;
    });
}

function submitQuickAdd() {
    var apiKey = document.getElementById('qaApiKey').value.trim();
    if (!apiKey) { showToast('请输入 API Key', 'warning'); return; }

    var selectedIds = [];
    document.querySelectorAll('#qaModelList input[type="checkbox"]:checked:not(:disabled)').forEach(function(cb) {
        selectedIds.push(cb.value);
    });
    if (selectedIds.length === 0) { showToast('请至少选择一个模型', 'warning'); return; }

    var visibleToAll = document.getElementById('qaVisible').checked;

    api('/api/admin/models/batch', {
        method: 'POST',
        body: JSON.stringify({
            providerId: quickAddProviderId,
            apiKey: apiKey,
            selectedModelIds: selectedIds,
            visibleToAll: visibleToAll
        })
    }).then(function(data) {
        if (data && data.success) {
            showToast(data.message || '接入成功', 'success');
            closeQuickAddModal();
            loadModels();
        } else {
            showToast((data && data.message) || '接入失败', 'error');
        }
    });
}

// ===== Providers =====
function loadProviders() {
    api('/api/admin/providers').then(function(data) {
        if (!data || !data.success) return;
        providers = data.data;
        var select = document.getElementById('providerSelect');
        select.innerHTML = '<option value="custom">自定义接入</option>';
        providers.forEach(function(p) {
            var opt = document.createElement('option');
            opt.value = p.id;
            opt.textContent = (p.icon || '') + ' ' + p.name;
            select.appendChild(opt);
        });
        renderProviderGrid();
    });
}

function updateThinkingBadge(supports) {
    var badge = document.getElementById('mThinkingBadge');
    var hidden = document.getElementById('mThinking');
    if (supports) {
        badge.style.display = 'inline-block';
        hidden.value = 'true';
    } else {
        badge.style.display = 'none';
        hidden.value = 'false';
    }
}

function onProviderChange() {
    var pid = document.getElementById('providerSelect').value;
    var modelRow = document.getElementById('providerModelRow');
    var modelSelect = document.getElementById('providerModelSelect');

    if (pid === 'custom') {
        modelRow.style.display = 'none';
        document.getElementById('mApiUrl').value = '';
        document.getElementById('mModelId').value = '';
        document.getElementById('mDisplayName').value = '';
        updateThinkingBadge(false);
        return;
    }

    modelRow.style.display = 'block';
    var provider = providers.find(function(p) { return p.id === pid; });
    if (!provider) return;

    modelSelect.innerHTML = '';
    provider.models.forEach(function(m) {
        var opt = document.createElement('option');
        opt.value = m.id;
        opt.textContent = m.name;
        opt.dataset.supportsThinking = m.supportsThinking;
        modelSelect.appendChild(opt);
    });

    document.getElementById('mApiUrl').value = provider.defaultApiUrl;
    onProviderModelChange();
}

function onProviderModelChange() {
    var modelSelect = document.getElementById('providerModelSelect');
    var opt = modelSelect.options[modelSelect.selectedIndex];
    if (!opt) return;
    document.getElementById('mModelId').value = opt.value;
    document.getElementById('mDisplayName').value = opt.textContent;
    updateThinkingBadge(opt.dataset.supportsThinking === 'true');
}

// ===== Models =====
function loadModels() {
    api('/api/admin/models').then(function(data) {
        if (!data || !data.success) return;
        allModels = data.data;
        renderModels();
        renderProviderGrid();
    });
}

function renderModels() {
    var tbody = document.querySelector('#modelTable tbody');
    tbody.innerHTML = '';
    allModels.forEach(function(m) {
        var tr = document.createElement('tr');
        var providerDisplay = m.providerName
            ? (m.providerIcon ? m.providerIcon + ' ' : '') + esc(m.providerName)
            : esc(m.providerId || 'custom');
        tr.innerHTML =
            '<td>' + esc(m.displayName) + '</td>' +
            '<td>' + providerDisplay + '</td>' +
            '<td><code class="model-id-code">' + esc(m.modelId) + '</code></td>' +
            '<td>' + (m.supportsThinking ? '<span class="badge badge-think">🧠 思考</span>' : '-') + '</td>' +
            '<td><span class="badge ' + (m.enabled ? 'badge-on' : 'badge-off') + '">' + (m.enabled ? '启用' : '禁用') + '</span></td>' +
            '<td><span class="badge ' + (m.visibleToAll ? 'badge-all' : 'badge-limited') + '">' + (m.visibleToAll ? '全员' : '限制') + '</span></td>' +
            '<td><button class="action-btn" onclick="editModel(\'' + m.id + '\')">编辑</button> ' +
            '<button class="danger-btn" onclick="deleteModel(\'' + m.id + '\')">删除</button></td>';
        tbody.appendChild(tr);
    });
}

function showAddModel() {
    document.getElementById('modalTitle').textContent = '添加模型';
    document.getElementById('editModelId').value = '';
    document.getElementById('modelForm').reset();
    document.getElementById('mEnabled').checked = true;
    document.getElementById('mVisible').checked = true;
    document.getElementById('providerModelRow').style.display = 'none';
    updateThinkingBadge(false);
    var apiKeyInput = document.getElementById('mApiKey');
    apiKeyInput.placeholder = 'sk-xxx';
    apiKeyInput.required = true;
    document.getElementById('modelModal').classList.add('active');
}

function editModel(id) {
    var m = allModels.find(function(x) { return x.id === id; });
    if (!m) return;
    document.getElementById('modalTitle').textContent = '编辑模型';
    document.getElementById('editModelId').value = id;
    document.getElementById('providerSelect').value = m.providerId || 'custom';
    onProviderChange();
    document.getElementById('mDisplayName').value = m.displayName;
    document.getElementById('mApiUrl').value = m.apiUrl;
    var apiKeyInput = document.getElementById('mApiKey');
    apiKeyInput.value = m.apiKey || '';
    apiKeyInput.placeholder = '修改请输入完整的新 API Key，留空则保留原值';
    apiKeyInput.required = false;
    document.getElementById('mModelId').value = m.modelId;
    updateThinkingBadge(m.supportsThinking);
    // 如果是内置模型，尝试选中对应的厂商模型
    if (m.providerId && m.providerId !== 'custom') {
        var modelSelect = document.getElementById('providerModelSelect');
        for (var i = 0; i < modelSelect.options.length; i++) {
            if (modelSelect.options[i].value === m.modelId) {
                modelSelect.selectedIndex = i;
                break;
            }
        }
    }
    document.getElementById('mEnabled').checked = m.enabled;
    document.getElementById('mVisible').checked = m.visibleToAll;
    document.getElementById('modelModal').classList.add('active');
}

function closeModal() { document.getElementById('modelModal').classList.remove('active'); }

function saveModel(e) {
    e.preventDefault();
    var id = document.getElementById('editModelId').value;
    var apiKey = document.getElementById('mApiKey').value.trim();
    // 添加模型时必须填写 API Key
    if (!id && !apiKey) {
        showToast('请填写 API Key', 'warning');
        return false;
    }
    // 编辑时，如果 apiKey 包含星号（脱敏值）则清空，让后端保留原值
    if (id && apiKey.indexOf('*') !== -1) {
        apiKey = '';
    }
    var body = {
        providerId: document.getElementById('providerSelect').value,
        displayName: document.getElementById('mDisplayName').value,
        apiUrl: document.getElementById('mApiUrl').value,
        apiKey: apiKey,
        modelId: document.getElementById('mModelId').value,
        protocol: document.getElementById('mProtocol').value,
        supportsThinking: document.getElementById('mThinking').value === 'true',
        enabled: document.getElementById('mEnabled').checked,
        visibleToAll: document.getElementById('mVisible').checked
    };

    var url = id ? '/api/admin/models/' + id : '/api/admin/models';
    var method = id ? 'PUT' : 'POST';

    api(url, { method: method, body: JSON.stringify(body) }).then(function(data) {
        if (data && data.success) {
            closeModal();
            loadModels();
        } else {
            showToast((data && data.message) || '操作失败', 'error');
        }
    });
    return false;
}

function deleteModel(id) {
    showDangerConfirm('确定删除该模型？此操作不可撤销。', '删除模型', function() {
        api('/api/admin/models/' + id, { method: 'DELETE' }).then(function(data) {
            if (data && data.success) { loadModels(); showToast('模型已删除', 'success'); }
            else showToast('删除失败', 'error');
        });
    });
}

// ===== Users =====
function loadUsers() {
    api('/api/admin/users').then(function(data) {
        if (!data || !data.success) return;
        allUsers = data.data;
        renderUsers();
    });
}

function renderUsers() {
    var tbody = document.querySelector('#userTable tbody');
    tbody.innerHTML = '';
    allUsers.forEach(function(u) {
        var tr = document.createElement('tr');
        tr.innerHTML =
            '<td>' + esc(u.username) + '</td>' +
            '<td><span class="badge ' + (u.role === 'admin' ? 'badge-on' : 'badge-all') + '">' + u.role + '</span></td>' +
            '<td>' + (u.createdAt || '-') + '</td>' +
            '<td>' + (u.lastLoginAt || '-') + '</td>' +
            '<td>' + (u.lastLoginIp || '-') + '</td>' +
            '<td>' + (u.lastLoginBrowser || '-') + '</td>' +
            '<td>' +
            '<button class="action-btn" onclick="showPerms(\'' + u.id + '\')">权限</button> ' +
            (u.role !== 'admin' ? '<button class="danger-btn" onclick="deleteUser(\'' + u.id + '\')">删除</button>' : '') +
            '</td>';
        tbody.appendChild(tr);
    });
}

function deleteUser(id) {
    showDangerConfirm('确定删除该用户？此操作不可撤销。', '删除用户', function() {
        api('/api/admin/users/' + id, { method: 'DELETE' }).then(function(data) {
            if (data && data.success) { loadUsers(); showToast('用户已删除', 'success'); }
            else showToast('删除失败', 'error');
        });
    });
}

function showPerms(userId) {
    editUserId = userId;
    var user = allUsers.find(function(u) { return u.id === userId; });
    if (!user) return;
    document.getElementById('permUsername').textContent = user.username;
    var list = document.getElementById('permList');
    list.innerHTML = '';
    allModels.forEach(function(m) {
        var checked = user.allowedModelIds && user.allowedModelIds.indexOf(m.id) >= 0;
        var item = document.createElement('div');
        item.className = 'perm-item';
        var providerDisplay = m.providerName || m.providerId || 'custom';
        item.innerHTML = '<input type="checkbox" value="' + m.id + '"' + (checked ? ' checked' : '') + '> ' +
            '<span>' + esc(m.displayName) + ' <small style="color:#94a3b8">(' + esc(providerDisplay) + ')</small>' +
            (m.visibleToAll ? ' <small style="color:#6366f1">(全员可见)</small>' : '') + '</span>';
        list.appendChild(item);
    });
    document.getElementById('permModal').classList.add('active');
}

function closePermModal() { document.getElementById('permModal').classList.remove('active'); }

function savePermissions() {
    var ids = [];
    document.querySelectorAll('#permList input:checked').forEach(function(cb) { ids.push(cb.value); });
    api('/api/admin/users/' + editUserId + '/permissions', {
        method: 'PUT',
        body: JSON.stringify({ allowedModelIds: ids })
    }).then(function(data) {
        if (data && data.success) {
            closePermModal();
            loadUsers();
        }
    });
}

// ===== Usage =====

// 子菜单切换
function switchDataSubTab(tabName) {
    document.querySelectorAll('.sub-menu-item').forEach(function(item) {
        item.classList.remove('active');
    });
    event.target.classList.add('active');
    document.querySelectorAll('.data-sub-content').forEach(function(c) { c.classList.remove('active'); });
    document.getElementById('data-sub-' + tabName).classList.add('active');
    if (tabName === 'usage') {
        loadUsage(1);
    } else if (tabName === 'stats') {
        loadUsageStats(1);
    }
}

// 下拉框填充逻辑
function loadFilterOptions() {
    api('/api/admin/usage/filters').then(function(data) {
        if (!data || !data.success) return;
        fillSelect('usageSearchUser', data.usernames, '全部用户');
        fillSelect('usageSearchModel', data.modelNames, '全部模型');
        fillSelect('statsSearchUser', data.usernames, '全部用户');
        fillSelect('statsSearchModel', data.modelNames, '全部模型');
    });
}

function fillSelect(selectId, options, defaultText) {
    var select = document.getElementById(selectId);
    if (!select) return;
    var currentVal = select.value;
    select.innerHTML = '<option value="">' + defaultText + '</option>';
    (options || []).forEach(function(name) {
        var opt = document.createElement('option');
        opt.value = name;
        opt.textContent = name;
        if (name === currentVal) opt.selected = true;
        select.appendChild(opt);
    });
}

// 使用记录加载（带分页）
function loadUsage(page) {
    page = page || 1;
    var username = document.getElementById('usageSearchUser') ? document.getElementById('usageSearchUser').value : '';
    var modelName = document.getElementById('usageSearchModel') ? document.getElementById('usageSearchModel').value : '';
    var date = document.getElementById('usageSearchDate') ? document.getElementById('usageSearchDate').value : '';
    var qs = ['page=' + page, 'size=20'];
    if (username) qs.push('username=' + encodeURIComponent(username));
    if (modelName) qs.push('modelName=' + encodeURIComponent(modelName));
    if (date) qs.push('date=' + encodeURIComponent(date));
    api('/api/admin/usage?' + qs.join('&')).then(function(data) {
        if (!data || !data.success) return;
        var tbody = document.querySelector('#usageTable tbody');
        tbody.innerHTML = '';
        var logs = data.data || [];
        if (logs.length === 0) {
            tbody.innerHTML = '<tr><td colspan="7" class="stats-empty-row">暂无使用记录</td></tr>';
        } else {
            logs.forEach(function(l) {
                var tr = document.createElement('tr');
                tr.innerHTML =
                    '<td>' + esc(l.timestamp || '-') + '</td>' +
                    '<td>' + esc(l.username || '-') + '</td>' +
                    '<td>' + esc(l.modelName || '-') + '</td>' +
                    '<td>' + fmtToken(l.promptTokens) + '</td>' +
                    '<td>' + fmtToken(l.completionTokens) + '</td>' +
                    '<td>' + fmtToken(l.cachedTokens) + '</td>' +
                    '<td>' + (l.deepThinking ? '<span class="badge badge-think">🧠 思考</span>' : '-') + '</td>';
                tbody.appendChild(tr);
            });
        }
        renderPagination('usagePagination', data.page, data.totalPages, loadUsage);
    });
}

function fmtToken(val) {
    if (!val || val === 0) return '<span class="token-zero">-</span>';
    return '' + val;
}

function esc(str) {
    if (!str) return '';
    var d = document.createElement('div');
    d.textContent = str;
    return d.innerHTML;
}

// 用户统计加载（带分页）
function loadUsageStats(page) {
    page = page || 1;
    var username = document.getElementById('statsSearchUser') ? document.getElementById('statsSearchUser').value : '';
    var modelName = document.getElementById('statsSearchModel') ? document.getElementById('statsSearchModel').value : '';
    var date = document.getElementById('statsSearchDate') ? document.getElementById('statsSearchDate').value : '';
    var qs = ['page=' + page, 'size=20'];
    if (username) qs.push('username=' + encodeURIComponent(username));
    if (modelName) qs.push('modelName=' + encodeURIComponent(modelName));
    if (date) qs.push('date=' + encodeURIComponent(date));
    api('/api/admin/usage/stats?' + qs.join('&')).then(function(data) {
        if (!data || !data.success) return;
        var tbody = document.querySelector('#statsTable tbody');
        tbody.innerHTML = '';
        var stats = data.data || [];
        if (stats.length === 0) {
            tbody.innerHTML = '<tr><td colspan="8" class="stats-empty-row">暂无统计数据</td></tr>';
        } else {
            stats.forEach(function(s) {
                var tr = document.createElement('tr');
                tr.innerHTML =
                    '<td>' + esc(s.username) + '</td>' +
                    '<td>' + esc(s.date) + '</td>' +
                    '<td>' + esc(s.modelName) + '</td>' +
                    '<td><strong>' + s.count + '</strong></td>' +
                    '<td>' + fmtToken(s.promptTokens) + '</td>' +
                    '<td>' + fmtToken(s.completionTokens) + '</td>' +
                    '<td>' + fmtToken(s.cachedTokens) + '</td>' +
                    '<td>' + (s.thinkingCount > 0 ? '<span class="badge badge-think">🧠 ' + s.thinkingCount + '</span>' : '-') + '</td>';
                tbody.appendChild(tr);
            });
        }
        renderPagination('statsPagination', data.page, data.totalPages, loadUsageStats);
    });
}

// 分页渲染
function renderPagination(containerId, currentPage, totalPages, callback) {
    var container = document.getElementById(containerId);
    if (!container) return;
    container.innerHTML = '';
    if (totalPages <= 1) return;

    var firstBtn = document.createElement('button');
    firstBtn.textContent = '首页';
    firstBtn.disabled = currentPage <= 1;
    firstBtn.onclick = function() { callback(1); };
    container.appendChild(firstBtn);

    var prevBtn = document.createElement('button');
    prevBtn.textContent = '上一页';
    prevBtn.disabled = currentPage <= 1;
    prevBtn.onclick = function() { callback(currentPage - 1); };
    container.appendChild(prevBtn);

    var startPage = Math.max(1, currentPage - 3);
    var endPage = Math.min(totalPages, startPage + 6);
    if (endPage - startPage < 6) startPage = Math.max(1, endPage - 6);

    for (var i = startPage; i <= endPage; i++) {
        var pageBtn = document.createElement('button');
        pageBtn.textContent = i;
        if (i === currentPage) pageBtn.className = 'active';
        (function(p) { pageBtn.onclick = function() { callback(p); }; })(i);
        container.appendChild(pageBtn);
    }

    var nextBtn = document.createElement('button');
    nextBtn.textContent = '下一页';
    nextBtn.disabled = currentPage >= totalPages;
    nextBtn.onclick = function() { callback(currentPage + 1); };
    container.appendChild(nextBtn);

    var lastBtn = document.createElement('button');
    lastBtn.textContent = '末页';
    lastBtn.disabled = currentPage >= totalPages;
    lastBtn.onclick = function() { callback(totalPages); };
    container.appendChild(lastBtn);

    var pageInfo = document.createElement('span');
    pageInfo.className = 'page-info';
    pageInfo.textContent = currentPage + ' / ' + totalPages;
    container.appendChild(pageInfo);
}

// 搜索使用记录
function searchUsage() {
    loadUsage(1);
}

// 重置使用记录筛选
function resetUsage() {
    var userSelect = document.getElementById('usageSearchUser');
    var modelSelect = document.getElementById('usageSearchModel');
    var dateInput = document.getElementById('usageSearchDate');
    if (userSelect) userSelect.value = '';
    if (modelSelect) modelSelect.value = '';
    if (dateInput) dateInput.value = '';
    loadUsage(1);
}

// 搜索用户统计
function searchUsageStats() {
    loadUsageStats(1);
}

// 重置用户统计筛选
function resetUsageStats() {
    var userSelect = document.getElementById('statsSearchUser');
    var modelSelect = document.getElementById('statsSearchModel');
    var dateInput = document.getElementById('statsSearchDate');
    if (userSelect) userSelect.value = '';
    if (modelSelect) modelSelect.value = '';
    if (dateInput) dateInput.value = '';
    loadUsageStats(1);
}
