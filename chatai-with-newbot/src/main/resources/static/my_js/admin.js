var TOKEN = localStorage.getItem('token');
var providers = [];
var allModels = [];
var allUsers = [];
var editUserId = null;
var quickAddProviderId = null;

if (!TOKEN || localStorage.getItem('role') !== 'admin') {
    window.location.href = '/login.html';
}

function headers() { return { 'Authorization': 'Bearer ' + TOKEN, 'Content-Type': 'application/json' }; }

function api(url, opts) {
    opts = opts || {};
    opts.headers = headers();
    return fetch(url, opts).then(function(r) {
        if (r.status === 401) { window.location.href = '/login.html'; return; }
        if (r.status === 403) { alert('无权限'); return; }
        return r.json();
    });
}

// ===== Init =====
(function() {
    loadProviders();
    loadModels();
    loadUsers();
    loadUsage();
})();

function switchSection(name) {
    document.querySelectorAll('.section').forEach(function(s) { s.classList.remove('active'); });
    document.querySelectorAll('.nav-tab').forEach(function(t) { t.classList.remove('active'); });
    document.getElementById('sec-' + name).classList.add('active');
    event.target.classList.add('active');
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
    if (!apiKey) { alert('请输入 API Key'); return; }

    var selectedIds = [];
    document.querySelectorAll('#qaModelList input[type="checkbox"]:checked:not(:disabled)').forEach(function(cb) {
        selectedIds.push(cb.value);
    });
    if (selectedIds.length === 0) { alert('请至少选择一个模型'); return; }

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
            alert(data.message || '接入成功');
            closeQuickAddModal();
            loadModels();
        } else {
            alert((data && data.message) || '接入失败');
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
    document.getElementById('mApiKey').value = m.apiKey;
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
    var body = {
        providerId: document.getElementById('providerSelect').value,
        displayName: document.getElementById('mDisplayName').value,
        apiUrl: document.getElementById('mApiUrl').value,
        apiKey: document.getElementById('mApiKey').value,
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
            alert((data && data.message) || '操作失败');
        }
    });
    return false;
}

function deleteModel(id) {
    if (!confirm('确定删除该模型？')) return;
    api('/api/admin/models/' + id, { method: 'DELETE' }).then(function(data) {
        if (data && data.success) loadModels();
        else alert('删除失败');
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
            '<td>' +
            '<button class="action-btn" onclick="showPerms(\'' + u.id + '\')">权限</button> ' +
            (u.role !== 'admin' ? '<button class="danger-btn" onclick="deleteUser(\'' + u.id + '\')">删除</button>' : '') +
            '</td>';
        tbody.appendChild(tr);
    });
}

function deleteUser(id) {
    if (!confirm('确定删除该用户？')) return;
    api('/api/admin/users/' + id, { method: 'DELETE' }).then(function(data) {
        if (data && data.success) loadUsers();
        else alert('删除失败');
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
function loadUsage() {
    api('/api/admin/usage').then(function(data) {
        if (!data || !data.success) return;
        var tbody = document.querySelector('#usageTable tbody');
        tbody.innerHTML = '';
        var logs = data.data || [];
        // 最新的在前面
        logs.reverse().forEach(function(l) {
            var tr = document.createElement('tr');
            tr.innerHTML = '<td>' + esc(l.timestamp || '-') + '</td><td>' + esc(l.username || '-') + '</td><td>' + esc(l.modelName || '-') + '</td>';
            tbody.appendChild(tr);
        });
    });
}

function esc(str) {
    if (!str) return '';
    var d = document.createElement('div');
    d.textContent = str;
    return d.innerHTML;
}
