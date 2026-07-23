// ===== 会话列表Loading动画 =====
function showChatListLoading() {
    var list = document.getElementById('chatList');
    if (!list) return;
    list.innerHTML = '<div class="chat-list-loading">' +
        '<div class="chat-list-skeleton"><span class="chat-list-skeleton-dot"></span><span class="chat-list-skeleton-bar long"></span></div>' +
        '<div class="chat-list-skeleton"><span class="chat-list-skeleton-dot"></span><span class="chat-list-skeleton-bar medium"></span></div>' +
        '<div class="chat-list-skeleton"><span class="chat-list-skeleton-dot"></span><span class="chat-list-skeleton-bar short"></span></div>' +
        '<div class="chat-list-skeleton"><span class="chat-list-skeleton-dot"></span><span class="chat-list-skeleton-bar long"></span></div>' +
        '<div class="chat-list-skeleton"><span class="chat-list-skeleton-dot"></span><span class="chat-list-skeleton-bar medium"></span></div>' +
        '</div>'; }
function hideChatListLoading() {
    // loading会在updateChatList()中自动清除，无需额外操作
}

// ===== 安全的localStorage访问封装 =====
var _storageAvailable = null;
function safeStorageGet(key) {
    try { return localStorage.getItem(key); } catch(e) {
        if (_storageAvailable === null) { console.warn('localStorage不可用，部分功能可能受限:', e.message); _storageAvailable = false; }
        return null;
    }
}
function safeStorageSet(key, value) {
    try { localStorage.setItem(key, value); } catch(e) {
        if (_storageAvailable === null) { console.warn('localStorage不可用，部分功能可能受限:', e.message); _storageAvailable = false; }
    }
}
function safeStorageRemove(key) {
    try { localStorage.removeItem(key); } catch(e) {}
}
function safeStorageParse(key) {
    try { var v = localStorage.getItem(key); return v ? JSON.parse(v) : null; } catch(e) { return null; }
}

// ===== 全局状态 =====
var TOKEN = safeStorageGet('token');
var CURRENT_USER = safeStorageGet('username') || '';
var currentChatId = null;
var chats = {};
var models = [];
var currentModelId = '';
var currentModelName = '';
var defaultModelId = null; // 全局默认模型ID（由后台配置，新会话自动选中）
var isDeepThinking = false;
var isStreamActive = false;
var currentController = null;
var buffer = '';
var currentThinkingContent = '';
var currentAnswerContent = '';
var thinkingStartTime = null;
var modelDropdownOpen = false; // 自定义下拉框状态
var streamUsage = null; // 流式响应中的usage数据
var pendingImages = []; // 待发送的图片base64列表
var MAX_IMAGE_SIZE = 5 * 1024 * 1024; // 单张图片最大5MB
var deletedChatIds = []; // 已删除的会话ID列表（用于服务端同步）
var isChatHistoryLoaded = false; // 标记会话历史是否已从服务端加载
var syncTimer = null; // 防抖定时器，避免频繁同步

// ===== 千分位格式化 =====
function fmtToken(n) {
    if (n === undefined || n === null || n === 0) return '0';
    return Number(n).toLocaleString('en-US');
}

// 厂商图标映射：provider id -> SVG图标路径
var providerIconMap = {
    'deepseek': '/icons/deepseek-icon.svg',
    'qwen': '/icons/qwen-icon.svg',
    'kimi': '/icons/kimi-icon.svg',
    'zhipu': '/icons/zhipu-icon.svg',
    'minimax': '/icons/minimax-icon.svg',
    'doubao': '/icons/doubao-icon.svg'
};

// Layui 模块加载
layui.use(['layer', 'form', 'element', 'jquery'], function() {
    var layer = layui.layer;
    var form = layui.form;
    var $ = layui.$;

    // 将 layer 和 $ 暴露到全局，方便其他函数使用
    window._layer = layer;
    window._$ = $;
    window._form = form;

    // 注入 MermaidRenderer 依赖(模块独立, 通过 configure 解耦)
    if (typeof MermaidRenderer !== 'undefined' && MermaidRenderer.configure) {
        MermaidRenderer.configure({
            toast: showToast,
            escapeHtml: escapeHtml,
            safeStorageGet: safeStorageGet,
            safeStorageSet: safeStorageSet
        });
    }

    // 初始化思考图标按钮
    var thinkIconBtn = document.getElementById('thinkIconBtn');
    if (thinkIconBtn) {
        thinkIconBtn.addEventListener('click', function(e) {
            e.stopPropagation();
            isDeepThinking = !isDeepThinking;
            thinkIconBtn.classList.toggle('active', isDeepThinking);
            console.log('深度思考图标切换:', isDeepThinking);
        });
    }

    // ===== 初始化 =====
    if (!TOKEN) { window.location.href = '/login.html'; return; }

    // 初始化marked（安全检查：CDN可能被浏览器扩展阻止加载）
    if (typeof marked !== 'undefined') {
        if (marked.use) {
            // v5+ 使用 marked.use + custom renderer（避免废弃的 highlight 参数）
            var hljsRenderer = {
                code: function(code, lang) {
                    var escaped = escapeHtml(code);
                    var cls = lang ? ' class="language-' + lang + '"' : '';
                    return '<pre><code' + cls + '>' + escaped + '</code></pre>';
                }
            };
            marked.use({ gfm: true, breaks: true, renderer: hljsRenderer });
            // 禁用v5+废弃参数，清除控制台警告
            marked.setOptions({ mangle: false, headerIds: false, headerPrefix: '' });
        } else if (marked.setOptions) {
            // 旧版本回退
            marked.setOptions({ gfm: true, breaks: true, mangle: false, headerIds: false, headerPrefix: '' });
        }
    }
    // mermaid 初始化已迁移到 my_js/mermaid-renderer.js(独立模块)

    // 显示用户信息
    document.getElementById('usernameDisplay').textContent = safeStorageGet('username') || '用户';
    if (safeStorageGet('role') === 'admin') {
        var adminMenuItem = document.getElementById('adminMenuItem');
        if (adminMenuItem) adminMenuItem.style.display = 'flex';
        // 兼容旧 DOM（若仍存在）
        var adminBtn = document.getElementById('adminBtn');
        if (adminBtn) adminBtn.style.display = 'flex';
    }

    // 显示会话列表加载动画
    showChatListLoading();
    // 从服务端加载会话历史（多端同步）
    loadChatHistoryFromServer(function() {
        loadModels();
        updateChatList();
        displayMessages();
    });

    // 绑定输入框事件
    var textarea = document.getElementById('userInput');
    textarea.addEventListener('input', autoResize);
    textarea.addEventListener('keydown', function(e) {
        if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(false); }
    });
    // 图片粘贴监听（多模态支持）
    textarea.addEventListener('paste', handlePasteImage);

    // 响应式
    window.addEventListener('resize', handleResize);
    if (window.innerWidth <= 768) {
        document.getElementById('sidebar').classList.add('collapsed');
    }

    // 滚动跟随管理器初始化（统一处理自动滚动/中断/恢复/节流/失焦/触摸）
    var chatContainer = document.getElementById('chatContainer');
    if (chatContainer && typeof ScrollFollowManager !== 'undefined') {
        ScrollFollowManager.init(chatContainer);
    }

    // 点击外部关闭模型下拉框
    document.addEventListener('click', function(e) {
        var area = document.getElementById('modelSelectArea');
        if (area && !area.contains(e.target)) {
            closeModelDropdown();
        }
    });
});

// ===== 认证相关 =====
function authHeaders() { return { 'Authorization': 'Bearer ' + TOKEN, 'Content-Type': 'application/json' }; }

// 根据 401 响应头判断未授权原因：IP 变更或登录过期
function authFailMsg(r) {
    try {
        if (r && r.headers && r.headers.get('X-Auth-Reason') === 'ip_changed') {
            return '登录IP已变更，请重新登录';
        }
    } catch (e) {}
    return '登录已过期，请重新登录';
}

function logout() {
    if (window._layer) {
        window._layer.confirm('确定要退出当前账号吗？', {
            title: '退出登录',
            btn: ['退出', '取消'],
            btn1: function(index) {
                window._layer.close(index);
                doLogout();
            }
        });
    } else {
        doLogout();
    }
}

function showConfirmDialog(msg, title, onOk) {
    if (window._layer) {
        window._layer.confirm(msg, {
            title: title || '确认',
            btn: ['确定', '取消'],
            btn1: function(index) {
                window._layer.close(index);
                if (onOk) onOk();
            }
        });
    } else {
        if (confirm(msg)) { if (onOk) onOk(); }
    }
}

function doLogout() {
    fetch('/api/auth/logout', { method: 'POST', headers: authHeaders() }).catch(function(){});
    safeStorageRemove('token');
    safeStorageRemove('username');
    safeStorageRemove('role');
    window.location.href = '/login.html';
}

function goAdmin() { window.location.href = '/admin.html'; }

// ===== 模型加载 =====
function loadModels() {
    fetch('/api/models', { headers: authHeaders() })
    .then(function(r) {
        if (r.status === 401) {
            safeStorageRemove('token');
            safeStorageRemove('username');
            safeStorageRemove('role');
            showToast(authFailMsg(r));
            setTimeout(function() { window.location.href = '/login.html'; }, 1500);
            return;
        }
        return r.json();
    })
    .then(function(data) {
        if (!data || !data.success) return;
        models = data.data;
        defaultModelId = data.defaultModelId || null;
        // 初始模型：优先默认模型（需在当前用户可见列表中），否则取第一个
        var initial = (models && models.length > 0) ? (findModelById(defaultModelId) || models[0]) : null;
        if (initial) {
            currentModelId = initial.id;
            currentModelName = initial.displayName;
            updateModelSelectDisplay(initial);
            // 存储当前模型的多模态支持状态
            window._currentModelSupportsMultimodal = initial.supportsMultimodal || false;
        }
        renderModelSelector(models);
        // 初始化深度思考状态（在renderModelSelector之后，确保DOM已就绪）
        if (initial) {
            isDeepThinking = false; // 默认关闭深度思考
            var thinkIconBtn = document.getElementById('thinkIconBtn');
            if (thinkIconBtn) thinkIconBtn.classList.toggle('active', false);
            updateThinkToggle(initial, false);
        }
    }).catch(function(e) { console.error('加载模型失败:', e); });
}

// 按 ID 查找当前可见模型
function findModelById(id) {
    if (!id || !models) return null;
    for (var i = 0; i < models.length; i++) {
        if (models[i].id === id) return models[i];
    }
    return null;
}

// 切换到默认模型（新会话调用）；默认模型不可用时保持当前选择
function applyDefaultModel() {
    var dm = findModelById(defaultModelId);
    if (dm) selectModel(dm);
}

// 根据模型displayName推断providerId
function inferProviderId(model) {
    if (model.providerId) return model.providerId;
    var name = (model.displayName || '').toLowerCase();
    var id = (model.modelId || '').toLowerCase();
    if (name.indexOf('deepseek') >= 0 || id.indexOf('deepseek') >= 0) return 'deepseek';
    if (name.indexOf('qwen') >= 0 || name.indexOf('通义') >= 0 || id.indexOf('qwen') >= 0 || id.indexOf('qwq') >= 0) return 'qwen';
    if (name.indexOf('kimi') >= 0 || name.indexOf('moonshot') >= 0 || id.indexOf('kimi') >= 0 || id.indexOf('moonshot') >= 0) return 'kimi';
    if (name.indexOf('glm') >= 0 || name.indexOf('智谱') >= 0 || id.indexOf('glm') >= 0) return 'zhipu';
    if (name.indexOf('minimax') >= 0 || id.indexOf('minimax') >= 0) return 'minimax';
    if (name.indexOf('豆包') >= 0 || name.indexOf('doubao') >= 0 || id.indexOf('doubao') >= 0) return 'doubao';
    return '';
}

// ===== 自定义模型选择下拉框（级联选择器）=====
// 全局缓存：当前所有模型按厂商分组的结果，避免每次重渲染都重新计算
var modelGroupedCache = null; // { groups: [{key, name, providerId, providerIcon, models:[...]}], order:[...] }

// 触发器上的 icon HTML（与下拉列中复用同一函数）
function buildProviderIconHtml(providerId, providerIcon) {
    var iconPath = providerIconMap[providerId];
    if (iconPath) {
        return '<img src="' + iconPath + '" class="cascader-item-icon" />';
    }
    if (providerIcon) {
        return '<span class="cascader-item-emoji">' + escapeHtml(providerIcon) + '</span>';
    }
    return '';
}

// 把 modelsData 按厂商分组并缓存到 modelGroupedCache
function buildModelGroups(modelsData) {
    var groups = {};
    var order = [];
    modelsData.forEach(function(m) {
        var key = m.providerName || m.providerId || 'custom';
        if (!groups[key]) {
            groups[key] = {
                key: key,
                name: key,
                providerId: m.providerId || inferProviderId(m),
                providerIcon: m.providerIcon || '',
                models: []
            };
            order.push(key);
        }
        groups[key].models.push(m);
    });
    // 同一组内，providerIcon 取第一个非空的（个别模型可能为空）
    order.forEach(function(k) {
        var g = groups[k];
        if (!g.providerIcon) {
            for (var i = 0; i < g.models.length; i++) {
                if (g.models[i].providerIcon) { g.providerIcon = g.models[i].providerIcon; break; }
            }
        }
    });
    modelGroupedCache = { groups: groups, order: order };
    return modelGroupedCache;
}

// 渲染级联容器：所有厂商的模型组一次性预渲染，
// hover 切换时只切 is-active（visibility），不再重排 DOM，避免闪烁
function renderModelSelector(modelsData) {
    var dropdown = document.getElementById('modelSelectDropdown');
    if (!dropdown) return;
    dropdown.innerHTML = '';

    var cache = buildModelGroups(modelsData);
    if (!cache.order.length) return;

    // 当前选中模型对应的厂商 key（用于默认激活第二列）
    var currentModel = findModelById(currentModelId);
    var activeProviderKey = (currentModel && (currentModel.providerName || currentModel.providerId)) || cache.order[0];

    // 两列容器
    var cascader = document.createElement('div');
    cascader.className = 'model-cascader';

    var col1 = document.createElement('div');
    col1.className = 'cascader-col cascader-col-providers';
    col1.setAttribute('data-col', 'providers');

    var col2 = document.createElement('div');
    col2.className = 'cascader-col cascader-col-models';
    col2.setAttribute('data-col', 'models');

    cascader.appendChild(col1);
    cascader.appendChild(col2);
    dropdown.appendChild(cascader);

    // 渲染第一列：厂商列表
    cache.order.forEach(function(key) {
        var g = cache.groups[key];
        var item = document.createElement('div');
        item.className = 'cascader-item cascader-provider' + (key === activeProviderKey ? ' is-active' : '');
        item.setAttribute('data-provider-key', key);
        item.innerHTML =
            buildProviderIconHtml(g.providerId, g.providerIcon) +
            '<span class="cascader-item-name">' + escapeHtml(g.name) + '</span>' +
            '<svg class="cascader-item-arrow" width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><polyline points="9 18 15 12 9 6"/></svg>';
        // hover 切换 is-active（只切类，不再重渲染第二列）
        item.addEventListener('mouseenter', function() {
            setActiveProvider(key);
        });
        // 移动端 fallback：点击厂商也激活
        item.addEventListener('click', function(e) {
            e.stopPropagation();
            setActiveProvider(key);
        });
        col1.appendChild(item);
    });

    // 预渲染所有厂商的模型组（一次性渲染，hover 时只切 visibility）
    cache.order.forEach(function(key) {
        var g = cache.groups[key];
        var group = document.createElement('div');
        group.className = 'cascader-models-group' + (key === activeProviderKey ? ' is-active' : '');
        group.setAttribute('data-provider-key', key);
        if (g.models && g.models.length) {
            g.models.forEach(function(m) {
                var item = document.createElement('div');
                item.className = 'cascader-item cascader-model' + (m.id === currentModelId ? ' is-selected' : '');
                item.setAttribute('data-model-id', m.id);
                item.setAttribute('data-supports-thinking', m.supportsThinking ? 'true' : 'false');
                var iconHtml = '';
                if (m.providerIcon) {
                    iconHtml = '<span class="cascader-item-emoji">' + escapeHtml(m.providerIcon) + '</span>';
                } else if (providerIconMap[m.providerId || g.providerId]) {
                    iconHtml = '<img src="' + providerIconMap[m.providerId || g.providerId] + '" class="cascader-item-icon" />';
                }
                item.innerHTML =
                    iconHtml +
                    '<span class="cascader-item-name">' + escapeHtml(m.displayName) + '</span>' +
                    '<svg class="cascader-check" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg>';
                item.addEventListener('click', function(e) {
                    e.stopPropagation();
                    selectModel(m);
                });
                group.appendChild(item);
            });
        } else {
            var empty = document.createElement('div');
            empty.className = 'cascader-col-empty';
            empty.textContent = '该厂商下暂无模型';
            group.appendChild(empty);
        }
        col2.appendChild(group);
    });

    if (cache.order.length > 0) {
        dropdown.classList.add('has-second');
        cascader.classList.add('has-second');
    }

    // 在浏览器布局完成后，测量所有模型组的最大自然高度，并锁定第二列高度
    // 这样切换厂商时下拉面板高度不会变化，彻底避免闪烁
    // 移动端（≤768px）使用 CSS 弹性高度，不做 JS 锁定
    requestAnimationFrame(function() {
        if (window.innerWidth <= 768) return;
        var groups = col2.querySelectorAll('.cascader-models-group');
        // 一次性临时把全部组改为 static（参与文档流）以测量真实高度
        var saved = [];
        Array.prototype.forEach.call(groups, function(g) {
            saved.push({ pos: g.style.position, vis: g.style.visibility });
            g.style.position = 'static';
            g.style.visibility = 'hidden';
        });
        var maxH = 0;
        Array.prototype.forEach.call(groups, function(g) {
            if (g.offsetHeight > maxH) maxH = g.offsetHeight;
        });
        // 恢复原始内联样式（CSS 类的 visibility/position 重新生效）
        Array.prototype.forEach.call(groups, function(g, i) {
            g.style.position = saved[i].pos;
            g.style.visibility = saved[i].vis;
        });

        // 第二列高度策略：
        //   - 至少 5 行（200px），避免父节点少时下拉过窄
        //   - 按最高模型组自然高度扩展
        //   - 上限 500px（12 行），超过时组内滚动而不是无限拉高
        // CSS 的 min-height/max-height 也会兜底，这里做一次 JS 钳制保证稳定
        var col2MinH = 200;
        var col2MaxH = 500;
        var col2H = maxH > 0 ? maxH : col2MinH;
        if (col2H < col2MinH) col2H = col2MinH;
        if (col2H > col2MaxH) col2H = col2MaxH;
        col2.style.height = col2H + 'px';
    });
}

// 切换第一列 active 状态 + 切换第二列对应组的 visibility
// 不再重渲染 DOM，零布局抖动
function setActiveProvider(providerKey, opts) {
    var dropdown = document.getElementById('modelSelectDropdown');
    if (!dropdown) return;
    var col1 = dropdown.querySelector('.cascader-col-providers');
    var col2 = dropdown.querySelector('.cascader-col-models');
    if (!col1 || !col2) return;
    Array.prototype.forEach.call(col1.querySelectorAll('.cascader-provider'), function(el) {
        if (el.getAttribute('data-provider-key') === providerKey) el.classList.add('is-active');
        else el.classList.remove('is-active');
    });
    Array.prototype.forEach.call(col2.querySelectorAll('.cascader-models-group'), function(el) {
        if (el.getAttribute('data-provider-key') === providerKey) el.classList.add('is-active');
        else el.classList.remove('is-active');
    });
    // 需要滚动到当前选中项时（仅打开下拉时使用，hover 切换不滚）
    if (opts && opts.scroll) {
        var activeGroup = col2.querySelector('.cascader-models-group.is-active');
        if (activeGroup) {
            var selected = activeGroup.querySelector('.cascader-model.is-selected');
            if (selected && selected.scrollIntoView) {
                try { selected.scrollIntoView({ block: 'nearest' }); } catch(e) {}
            }
        }
    }
}

function selectModel(model) {
    var prevModelId = currentModelId;
    currentModelId = model.id;
    currentModelName = model.displayName;
    // 存储当前模型的多模态支持状态
    window._currentModelSupportsMultimodal = model.supportsMultimodal || false;
    updateModelSelectDisplay(model);
    updateThinkToggle(model, prevModelId !== model.id);
    closeModelDropdown();
    // 更新第二列的选中态（不重渲染整个结构，仅切 class）
    var dropdown = document.getElementById('modelSelectDropdown');
    if (dropdown) {
        Array.prototype.forEach.call(dropdown.querySelectorAll('.cascader-model'), function(item) {
            if (item.getAttribute('data-model-id') === model.id) item.classList.add('is-selected');
            else item.classList.remove('is-selected');
        });
    }
}

// 触发器显示：厂商 / 模型（不再截断）
function updateModelSelectDisplay(model) {
    var valueEl = document.getElementById('modelSelectValue');
    if (!valueEl) return;
    var providerName = model.providerName || model.providerId || '';
    var pid = model.providerId || inferProviderId(model);
    var iconHtml = '';
    if (providerIconMap[pid]) {
        iconHtml = '<img src="' + providerIconMap[pid] + '" class="model-select-icon" />';
    } else if (model.providerIcon) {
        iconHtml = '<span class="model-select-emoji" style="font-size:14px;display:inline-flex;align-items:center;justify-content:center;width:18px;height:18px;">' + escapeHtml(model.providerIcon) + '</span>';
    }
    var providerSpan = providerName
        ? '<span class="msv-provider">' + escapeHtml(providerName) + '</span>'
        : '';
    var sep = providerName ? '<span class="msv-sep">/</span>' : '';
    valueEl.innerHTML =
        iconHtml +
        providerSpan +
        sep +
        '<span class="msv-name">' + escapeHtml(model.displayName) + '</span>';
}

function toggleModelDropdown() {
    if (modelDropdownOpen) {
        closeModelDropdown();
    } else {
        openModelDropdown();
    }
}

function openModelDropdown() {
    var dropdown = document.getElementById('modelSelectDropdown');
    var trigger = document.getElementById('modelSelectTrigger');
    if (dropdown) {
        dropdown.classList.add('active');
        modelDropdownOpen = true;
        // 自适应：判断下拉框是否有足够空间向下展开，不够则向上展开
        setTimeout(function() {
            var rect = trigger.getBoundingClientRect();
            var dropdownHeight = dropdown.scrollHeight;
            var spaceBelow = window.innerHeight - rect.bottom;
            var spaceAbove = rect.top;

            // 移动端：用 fixed 定位，动态计算位置紧贴触发按钮
            if (window.innerWidth <= 768) {
                if (spaceAbove >= dropdownHeight + 10 || spaceAbove > spaceBelow) {
                    // 向上展开：浮窗底边紧贴触发按钮顶部
                    dropdown.style.bottom = (window.innerHeight - rect.top + 6) + 'px';
                    dropdown.style.top = 'auto';
                    dropdown.classList.add('dropup');
                } else {
                    // 向下展开：浮窗顶边紧贴触发按钮底部
                    dropdown.style.top = (rect.bottom + 6) + 'px';
                    dropdown.style.bottom = 'auto';
                    dropdown.classList.remove('dropup');
                }
            } else {
                dropdown.style.bottom = '';
                dropdown.style.top = '';
                if (spaceBelow < dropdownHeight + 10 && spaceAbove > spaceBelow) {
                    dropdown.classList.add('dropup');
                } else {
                    dropdown.classList.remove('dropup');
                }
            }

            // 自动定位到当前选中项：找到当前模型所在的厂商，激活其列
            var currentModel = findModelById(currentModelId);
            if (currentModel && modelGroupedCache) {
                var key = currentModel.providerName || currentModel.providerId;
                if (modelGroupedCache.groups[key]) {
                    setActiveProvider(key, { scroll: true });
                }
            }
        }, 0);
    }
    if (trigger) {
        trigger.classList.add('active');
    }
}

function closeModelDropdown() {
    var dropdown = document.getElementById('modelSelectDropdown');
    var trigger = document.getElementById('modelSelectTrigger');
    if (dropdown) {
        dropdown.classList.remove('active');
        dropdown.classList.remove('dropup');
        // 清除移动端动态定位的内联样式
        dropdown.style.bottom = '';
        dropdown.style.top = '';
        modelDropdownOpen = false;
    }
    if (trigger) {
        trigger.classList.remove('active');
    }
}

// 更新深度思考开关
function updateThinkToggle(model, isModelChanged) {
    var thinkIconBtn = document.getElementById('thinkIconBtn');
    var toolbarDivider = document.getElementById('toolbarDivider');
    if (!thinkIconBtn) return;
    if (model.supportsThinking) {
        thinkIconBtn.style.display = 'flex';
        if (toolbarDivider) toolbarDivider.style.display = '';
        if (isModelChanged) {
            // 切换到新模型时，默认关闭深度思考
            isDeepThinking = false;
        }
        thinkIconBtn.classList.toggle('active', isDeepThinking);
    } else {
        thinkIconBtn.style.display = 'none';
        if (toolbarDivider) toolbarDivider.style.display = 'none';
        isDeepThinking = false;
    }
}

// toggleDeepThinking 已由 Layui form.on('switch(deepThink)') 处理

// ===== 会话管理 =====
function newChat() {
    if (isStreamActive) { showToast('请等待回答完成'); return; }
    // 如果当前会话为空（没有用户消息），则不再新建
    if (currentChatId && chats[currentChatId]) {
        var hasUserMsg = false;
        for (var i = 0; i < chats[currentChatId].length; i++) {
            if (chats[currentChatId][i].role === 'user') { hasUserMsg = true; break; }
        }
        if (!hasUserMsg) { showToast('当前已是新会话'); return; }
    }
    currentChatId = Date.now().toString();
    chats[currentChatId] = [];
    saveChats();
    updateChatList();
    displayMessages();
    resetState();
    // 新会话自动切换到默认模型
    applyDefaultModel();
}

function switchChat(id) {
    if (isStreamActive) { showToast('请等待回答完成'); return; }
    currentChatId = id;
    saveChats(); // 同步lastChatId到服务端
    resetState();
    updateChatList();
    displayMessages();
    // 移动端：选中会话后收起侧边栏与遮罩，避免遮罩残留罩住整页
    if (window.innerWidth <= 768) {
        var sb = document.getElementById('sidebar');
        if (sb && sb.classList.contains('open')) {
            sb.classList.remove('open');
            sb.classList.add('collapsed');
            toggleOverlay(false);
        }
    }
}

function deleteChat(id, e) {
    e.stopPropagation();
    showConfirmDialog('确定删除该会话？', '删除会话', function() {
        delete chats[id];
        // 记录已删除的会话ID，用于服务端同步
        deletedChatIds.push(id);
        saveChats();
        if (id === currentChatId) {
            // 优先跳转到已有的空会话（没有用户消息的"新会话"），避免重复创建
            var emptyId = findEmptyChatId();
            if (emptyId) {
                currentChatId = emptyId;
                resetState();
                updateChatList();
                displayMessages();
            } else {
                newChat();
            }
        } else {
            updateChatList();
        }
    });
}

// 查找一个空的会话（没有用户消息的"新会话"）
function findEmptyChatId() {
    var ids = Object.keys(chats);
    for (var i = 0; i < ids.length; i++) {
        var msgs = chats[ids[i]];
        if (!msgs || msgs.length === 0) return ids[i];
        var hasUserMsg = false;
        for (var j = 0; j < msgs.length; j++) {
            if (msgs[j].role === 'user') { hasUserMsg = true; break; }
        }
        if (!hasUserMsg) return ids[i];
    }
    return null;
}

// 会话搜索关键词
var chatSearchKeyword = '';

function updateChatList() {
    var list = document.getElementById('chatList');
    list.innerHTML = '';

    // 构建会话信息列表
    var chatInfos = [];
    var ids = Object.keys(chats);
    ids.forEach(function(id) {
        var msgs = chats[id];
        var first = null;
        var lastTime = null;
        for (var i = 0; i < msgs.length; i++) {
            if (msgs[i].role === 'user' && !first) { first = msgs[i]; }
            if (msgs[i].time) {
                // time 格式为 "2026/5/13 15:30:45" 这样的中文本地化格式
                lastTime = msgs[i].time;
            }
        }
        var title = first ? first.content.substring(0, 20) : '新会话';
        chatInfos.push({
            id: id,
            title: title,
            fullContent: first ? first.content : '',
            lastTime: lastTime,
            lastTimeDate: parseDateFromStr(lastTime)
        });
    });

    // 模糊搜索过滤
    var keyword = chatSearchKeyword.trim().toLowerCase();
    if (keyword) {
        chatInfos = chatInfos.filter(function(info) {
            return info.title.toLowerCase().indexOf(keyword) >= 0 ||
                   info.fullContent.toLowerCase().indexOf(keyword) >= 0;
        });
    }

    // 按最后对话时间排序（最新的在上面）
    chatInfos.sort(function(a, b) {
        // 无时间的会话（新建空会话）视为最新，排在最前
        if (!a.lastTimeDate && !b.lastTimeDate) return 0;
        if (!a.lastTimeDate) return -1;
        if (!b.lastTimeDate) return 1;
        return b.lastTimeDate - a.lastTimeDate;
    });

    // 按日期分组
    var groups = {};
    var groupOrder = [];
    chatInfos.forEach(function(info) {
        var dateLabel = getDateLabel(info.lastTimeDate);
        if (!groups[dateLabel]) {
            groups[dateLabel] = [];
            groupOrder.push(dateLabel);
        }
        groups[dateLabel].push(info);
    });

    // 渲染分组和会话项
    groupOrder.forEach(function(dateLabel) {
        var groupHeader = document.createElement('div');
        groupHeader.className = 'chat-date-header';
        groupHeader.textContent = dateLabel;
        list.appendChild(groupHeader);

        groups[dateLabel].forEach(function(info) {
            var item = document.createElement('div');
            item.className = 'chat-item' + (info.id === currentChatId ? ' active' : '');
            item.onclick = function() { switchChat(info.id); };
            item.innerHTML = '<span class="title">' + escapeHtml(info.title) + '</span>' +
                '<button class="delete-btn" onclick="deleteChat(\'' + info.id + '\', event)" title="删除">' +
                '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>' +
                '</button>';
            list.appendChild(item);
        });
    });

    // 无搜索结果提示
    if (keyword && chatInfos.length === 0) {
        var emptyTip = document.createElement('div');
        emptyTip.className = 'chat-search-empty';
        emptyTip.textContent = '未找到匹配的会话';
        list.appendChild(emptyTip);
    }

    // 更新标题
    var msgs = chats[currentChatId] || [];
    var firstUser = null;
    for (var i = 0; i < msgs.length; i++) { if (msgs[i].role === 'user') { firstUser = msgs[i]; break; } }
    document.getElementById('chatTitle').textContent = firstUser ?
        firstUser.content.substring(0, 30) + (firstUser.content.length > 30 ? '...' : '') : '新会话';
}

// 从时间字符串解析Date对象
function parseDateFromStr(timeStr) {
    if (!timeStr) return null;
    try {
        // 处理 "2026/5/13 15:30:45" 格式
        var date = new Date(timeStr.replace(/\//g, '-'));
        if (!isNaN(date.getTime())) return date;
        // 尝试直接解析
        date = new Date(timeStr);
        if (!isNaN(date.getTime())) return date;
    } catch(e) {}
    return null;
}

// 获取日期分组标签
function getDateLabel(date) {
    if (!date) return '今天'; // 新会话无消息时归入"今天"
    var now = new Date();
    var today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    var chatDate = new Date(date.getFullYear(), date.getMonth(), date.getDate());
    var diffDays = Math.floor((today.getTime() - chatDate.getTime()) / 86400000);

    if (diffDays === 0) return '今天';
    if (diffDays <= 30) return '30天内';

    // 超过30天，按月份分组
    var year = date.getFullYear();
    var month = date.getMonth() + 1;
    if (year === now.getFullYear()) {
        return month + '月';
    }
    return year + '年' + month + '月';
}

// 搜索会话
function filterChatList(keyword) {
    chatSearchKeyword = keyword;
    var clearBtn = document.getElementById('chatSearchClear');
    if (clearBtn) {
        clearBtn.style.display = keyword ? 'flex' : 'none';
    }
    updateChatList();
}

// 清空搜索
function clearChatSearch() {
    var input = document.getElementById('chatSearchInput');
    if (input) input.value = '';
    chatSearchKeyword = '';
    var clearBtn = document.getElementById('chatSearchClear');
    if (clearBtn) clearBtn.style.display = 'none';
    updateChatList();
}

// ===== 服务端会话同步（多端统一） =====

/**
 * 从服务端加载会话历史
 * @param callback 加载完成后的回调函数
 */
function loadChatHistoryFromServer(callback) {
    fetch('/api/chat/history', {
        method: 'GET',
        headers: authHeaders()
    }).then(function(r) {
        if (r.status === 401) {
            safeStorageRemove('token');
            safeStorageRemove('username');
            safeStorageRemove('role');
            showToast(authFailMsg(r));
            setTimeout(function() { window.location.href = '/login.html'; }, 1500);
            return null;
        }
        return r.json();
    }).then(function(data) {
        if (!data || !data.success) {
            // 加载失败，使用空数据
            chats = {};
            currentChatId = null;
            newChat();
            isChatHistoryLoaded = true;
            if (callback) callback();
            return;
        }
        // 从服务端数据恢复会话
        chats = data.chats || {};
        deletedChatIds = data.deletedChatIds || [];
        var lastId = data.lastChatId;
        if (lastId && chats[lastId]) {
            currentChatId = lastId;
        } else {
            // 没有有效的lastChatId，创建新会话
            currentChatId = null;
            newChat();
        }
        isChatHistoryLoaded = true;
        console.log('已从服务端加载会话历史，共 ' + Object.keys(chats).length + ' 个会话');
        if (callback) callback();
    }).catch(function(e) {
        console.error('加载会话历史失败:', e);
        // 加载失败，使用空数据
        chats = {};
        currentChatId = null;
        newChat();
        isChatHistoryLoaded = true;
        if (callback) callback();
    });
}

/**
 * 将会话数据同步到服务端（带防抖，避免频繁请求）
 */
function syncChatsToServer() {
    if (!isChatHistoryLoaded) return; // 尚未从服务端加载完成，不同步
    if (syncTimer) clearTimeout(syncTimer);
    syncTimer = setTimeout(function() {
        var payload = {
            lastChatId: currentChatId,
            chats: chats,
            deletedChatIds: deletedChatIds
        };
        fetch('/api/chat/history', {
            method: 'POST',
            headers: authHeaders(),
            body: JSON.stringify(payload)
        }).then(function(r) {
            if (r.status === 401) {
                safeStorageRemove('token');
                safeStorageRemove('username');
                safeStorageRemove('role');
                showToast(authFailMsg(r));
                setTimeout(function() { window.location.href = '/login.html'; }, 1500);
                return null;
            }
            return r.json();
        }).then(function(data) {
            if (data && data.success) {
                console.log('会话已同步到服务端');
            } else if (data) {
                console.warn('会话同步失败:', data.message);
            }
        }).catch(function(e) {
            console.error('会话同步请求失败:', e);
        });
    }, 500); // 500ms防抖
}

function saveChats() {
    // 同步到服务端（多端统一）
    syncChatsToServer();
}

function exportChats() {
    var text = '';
    Object.entries(chats).forEach(function(entry, idx) {
        if (idx > 0) text += '\n====================\n\n';
        entry[1].forEach(function(m) { text += '[' + (m.time || '') + '] ' + m.role + ': ' + m.content + '\n'; });
    });
    var blob = new Blob([text], {type: 'text/plain;charset=utf-8'});
    var a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'chat-export-' + new Date().toISOString().slice(0,10) + '.txt';
    a.click();
    showToast('已导出');
}

// ===== 发送消息 =====
function sendMessage(fromButton) {
    var input = document.getElementById('userInput');
    var btn = document.getElementById('sendButton');

    // 如果正在流式输出
    if (isStreamActive && currentController) {
        if (!fromButton) {
            showToast('当前还有内容没回答完，请点击右侧停止按钮中断');
            return;
        }
        currentController.abort();
        finishStream(true);
        return;
    }

    var text = input.value.trim();
    var hasImages = pendingImages.length > 0;
    // 有图片时检查多模态支持
    if (hasImages && !window._currentModelSupportsMultimodal) {
        showToast('当前模型不支持图像理解，请删除图片后再发送');
        return;
    }
    if (!text && !hasImages) return;
    if (!currentModelId) return;

    var userMsg = { role: 'user', content: text || '(图片)', time: nowStr() };
    // 将图片保存到用户消息中（用于历史记录显示）
    if (hasImages) {
        userMsg.images = pendingImages.slice();
    }
    if (!chats[currentChatId]) chats[currentChatId] = [];
    chats[currentChatId].push(userMsg);
    saveChats();
    input.value = '';
    input.style.height = 'auto';
    // 清除图片预览区
    clearPendingImages();

    displayMessages();
    updateChatList();
    showLoading();

    // 准备请求 - 直接使用全局变量 isDeepThinking
    var actualDeepThinking = isDeepThinking;
    console.log('发送消息，深度思考:', actualDeepThinking);
    var requestBody = {
        modelConfigId: currentModelId,
        // 全局提示词（system 消息）由后端根据当前登录用户统一注入到消息列表首位，前端不再携带
        messages: chats[currentChatId].filter(function(m) {
            // 过滤掉content为空的assistant消息，避免API报400错误
            return m.role === 'user' || (m.role === 'assistant' && m.content && m.content.trim());
        }).map(function(m) {
            // 将用户消息中的images字段传递给后端
            if (m.role === 'user' && m.images && m.images.length > 0) {
                return { role: m.role, content: m.content, images: m.images };
            }
            return { role: m.role, content: m.content };
        }),
        stream: true,
        deepThinking: actualDeepThinking,
        temperature: 0.7
    };

    isStreamActive = true;
    btn.classList.add('stop');
    btn.innerHTML = '<svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>';
    currentController = new AbortController();
    buffer = '';
    currentThinkingContent = '';
    currentAnswerContent = '';
    thinkingStartTime = null;
    streamUsage = null;

    var assistantMsg = { role: 'assistant', content: '', time: null, modelName: currentModelName || undefined };
    var hasResponse = false;

    fetch('/api/chat', {
        method: 'POST',
        headers: { 'Authorization': 'Bearer ' + TOKEN, 'Content-Type': 'application/json' },
        body: JSON.stringify(requestBody),
        signal: currentController.signal
    }).then(function(resp) {
        // 401未授权：提示登录过期并跳转登录页
        if (resp.status === 401) {
            hideLoading();
            resetState();
            resetSendBtn();
            // 移除已添加的用户消息
            chats[currentChatId].pop();
            saveChats();
            displayMessages();
            safeStorageRemove('token');
            safeStorageRemove('username');
            safeStorageRemove('role');
            showToast(authFailMsg(resp));
            setTimeout(function() { window.location.href = '/login.html'; }, 1500);
            return;
        }
        if (!resp.ok) {
            // 尝试读取服务端返回的错误信息
            return resp.text().then(function(text) {
                var errMsg = '服务器错误 (' + resp.status + ')';
                try {
                    var json = JSON.parse(text);
                    if (json.message) errMsg = json.message;
                    else if (json.error && json.error.message) errMsg = json.error.message;
                } catch(e) {}
                throw new Error(errMsg);
            });
        }
        var reader = resp.body.getReader();
        var decoder = new TextDecoder();

        function read() {
            return reader.read().then(function(result) {
                if (result.done) {
                    // 隐藏loading（如果还没有内容到达的话）
                    hideLoading();
                    // 处理buffer中残留的最后一条数据（如API错误消息）
                    if (buffer.trim()) {
                        var remainingLine = buffer.trim();
                        if (remainingLine.startsWith('{')) {
                            try {
                                var json = JSON.parse(remainingLine);
                                if (json.error) {
                                    currentAnswerContent += '\n\n**错误:** ' + (json.error.message || '未知错误');
                                    assistantMsg.content = currentAnswerContent;
                                    if (!assistantMsg.time) assistantMsg.time = nowStr();
                                    updateStreamingMessage(assistantMsg);
                                }
                            } catch(e) { /* skip parse error */ }
                        }
                    }
                    finishStream(false);
                    return;
                }
                var chunk = decoder.decode(result.value, {stream: true});
                buffer += chunk;
                var lines = buffer.split('data:');
                buffer = lines.pop() || '';
                var updated = false;

                lines.forEach(function(line) {
                    line = line.trim();
                    if (!line || line === '[DONE]') return;
                    if (line.startsWith('{')) {
                        try {
                            var json = JSON.parse(line);
                            if (json.error) {
                                currentAnswerContent += '\n\n**错误:** ' + (json.error.message || '未知错误');
                                updated = true;
                                return;
                            }
                            // 提取usage数据（token消耗）
                            if (json.usage) {
                                streamUsage = json.usage;
                            }
                            if (json.choices && json.choices[0] && json.choices[0].delta) {
                                var delta = json.choices[0].delta;
                                if (!hasResponse) { hasResponse = true; assistantMsg.time = nowStr(); }

                                if (delta.reasoning_content) {
                                    if (!thinkingStartTime) thinkingStartTime = Date.now();
                                    currentThinkingContent += delta.reasoning_content;
                                    updated = true;
                                }
                                if (delta.content) {
                                    if (thinkingStartTime && !assistantMsg.thinkingTime) {
                                        assistantMsg.thinkingTime = Math.round((Date.now() - thinkingStartTime) / 1000);
                                    }
                                    currentAnswerContent += delta.content;
                                    updated = true;
                                }
                            }
                        } catch(e) { /* skip parse error */ }
                    }
                });

                if (updated) {
                    assistantMsg.content = currentAnswerContent;
                    assistantMsg.reasoning_content = currentThinkingContent;
                    updateStreamingMessage(assistantMsg);
                }
                return read();
            });
        }
        return read();
    }).catch(function(err) {
        hideLoading();
        if (err.name === 'AbortError') { finishStream(true); return; }
        console.error('请求失败:', err);
        // 显示具体错误信息
        assistantMsg.content = '❌ ' + err.message;
        assistantMsg.time = nowStr();
        chats[currentChatId].push(assistantMsg);
        saveChats();
        displayMessages();
        resetSendBtn();
        resetState();
    });
}

function finishStream(interrupted) {
    isStreamActive = false;
    hideLoading();
    // 清除流式mermaid渲染防抖定时器，确保最终渲染
    if (_streamingMermaidTimer) {
        clearTimeout(_streamingMermaidTimer);
        _streamingMermaidTimer = null;
    }
    if (currentThinkingContent || currentAnswerContent) {
        var content = currentAnswerContent;
        // 避免保存content为空的assistant消息，否则后续请求API会报400错误
        if (!content || !content.trim()) {
            content = interrupted ? '（回答已中断）' : '（无正式回答）';
        }
        var msg = {
            role: 'assistant',
            content: content,
            reasoning_content: currentThinkingContent || undefined,
            time: nowStr(),
            interrupted: interrupted || undefined,
            modelName: currentModelName || undefined
        };
        if (thinkingStartTime && !msg.thinkingTime) {
            msg.thinkingTime = Math.round((Date.now() - thinkingStartTime) / 1000);
        }
        // 保存token消耗数据到assistant消息
        if (streamUsage) {
            msg.promptTokens = streamUsage.prompt_tokens || 0;
            msg.completionTokens = streamUsage.completion_tokens || 0;
            msg.reasoningTokens = 0;
            // 提取思考/推理token
            if (streamUsage.completion_tokens_details && streamUsage.completion_tokens_details.reasoning_tokens) {
                msg.reasoningTokens = streamUsage.completion_tokens_details.reasoning_tokens;
            }
            msg.cachedTokens = 0;
            if (streamUsage.prompt_tokens_details && streamUsage.prompt_tokens_details.cached_tokens) {
                msg.cachedTokens = streamUsage.prompt_tokens_details.cached_tokens;
            }
        }
        // 计算本次增量输入token（仅本次对话新增的输入token，而非累计）
        if (streamUsage && streamUsage.prompt_tokens) {
            var turnInputTokens = streamUsage.prompt_tokens;
            // 查找上一个assistant消息的promptTokens（累计值），用于计算增量
            for (var j = chats[currentChatId].length - 1; j >= 0; j--) {
                if (chats[currentChatId][j].role === 'assistant' && chats[currentChatId][j].promptTokens) {
                    turnInputTokens = streamUsage.prompt_tokens - chats[currentChatId][j].promptTokens;
                    break;
                }
            }
            if (turnInputTokens < 0) turnInputTokens = streamUsage.prompt_tokens;
            // 更新user消息的promptTokens为增量值（仅本次输入token）
            for (var i = chats[currentChatId].length - 1; i >= 0; i--) {
                if (chats[currentChatId][i].role === 'user') {
                    chats[currentChatId][i].promptTokens = turnInputTokens;
                    break;
                }
            }
            // 在assistant消息上保存增量输入token，用于显示"总Token"
            msg.turnInputTokens = turnInputTokens;
        }
        chats[currentChatId].push(msg);
        saveChats();

        // 就地完成流式消息元素，避免全量DOM重建导致闪烁
        var streamEl = document.getElementById('streaming-msg');
        if (streamEl) {
            // 移除streaming标记，使其成为普通消息元素
            streamEl.removeAttribute('id');
            // 更新气泡内容为最终状态
            var bubble = streamEl.querySelector('.msg-bubble');
            if (bubble) {
                bubble.innerHTML = renderMsgContent(msg);
                bubble.setAttribute('data-raw', msg.content || '');
            }
            // 重建footer信息（包含模型、token消耗、复制按钮）
            var existingFooter = streamEl.querySelector('.msg-footer');
            if (existingFooter) {
                existingFooter.remove();
            }
            var footer = createAssistantFooter(msg);
            streamEl.appendChild(footer);
            // 处理特殊内容（代码高亮等），流结束后触发mermaid渲染
            processSpecialContent(streamEl);
        }
    } else {
        // 没有任何内容，移除可能残留的streaming元素
        var streamEl = document.getElementById('streaming-msg');
        if (streamEl) streamEl.remove();
    }
    resetState();
    // 不再全量重建DOM，仅在必要时刷新（如切换会话后回来）
    // displayMessages() 会导致闪烁，改为仅更新滚动位置（通过 ScrollFollowManager 统一处理）
    // 流结束后同步滚动到底部，确保最终内容完全可见
    if (typeof ScrollFollowManager !== 'undefined') {
        ScrollFollowManager.syncScrollToBottom();
        ScrollFollowManager.updateNavButtons();
    } else {
        var container = document.getElementById('chatContainer');
        if (container) {
            var isNearBottom = container.scrollHeight - container.scrollTop - container.clientHeight < 80;
            if (isNearBottom) {
                container.scrollTop = container.scrollHeight;
            }
            updateScrollNav();
        }
    }
    resetSendBtn();
}

function resetSendBtn() {
    var btn = document.getElementById('sendButton');
    btn.classList.remove('stop');
    btn.innerHTML = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>';
}

function resetState() {
    buffer = '';
    currentThinkingContent = '';
    currentAnswerContent = '';
    thinkingStartTime = null;
    isStreamActive = false;
    streamUsage = null;
}

// ===== 消息渲染 - 性能优化 =====
function displayMessages() {
    var container = document.getElementById('chatContainer');
    var msgs = chats[currentChatId] || [];
    // 使用DocumentFragment减少回流
    var frag = document.createDocumentFragment();
    msgs.forEach(function(m) { frag.appendChild(createMessageEl(m)); });
    container.innerHTML = '';
    container.appendChild(frag);
    processSpecialContent(container);
    // 切换会话时重置跟随状态并瞬间跳到底部（通过 ScrollFollowManager 统一处理）
    if (typeof ScrollFollowManager !== 'undefined') {
        ScrollFollowManager.reset();
        ScrollFollowManager.scrollToBottomImmediate();
    } else {
        container.style.scrollBehavior = 'auto';
        container.scrollTop = container.scrollHeight;
        container.style.scrollBehavior = 'smooth';
        updateScrollNav();
    }
}

function updateStreamingMessage(msg) {
    var container = document.getElementById('chatContainer');
    // 查找或创建流式消息元素
    var streamEl = document.getElementById('streaming-msg');
    if (!streamEl) {
        // 首次有内容到达，隐藏loading动画
        hideLoading();
        streamEl = createMessageEl(msg);
        streamEl.id = 'streaming-msg';
        // 添加流式输出期间的footer（含token计算中动效）
        var existingFooter = streamEl.querySelector('.msg-footer');
        if (existingFooter) existingFooter.remove();
        streamEl.appendChild(createStreamingFooter(msg));
        appendToChat(streamEl);
    } else {
        var bubble = streamEl.querySelector('.msg-bubble');
        if (bubble) {
            var thinkingBody = bubble.querySelector('.thinking-body');
            var answerEl = bubble.querySelector('.answer-content');

            // 判断是否需要重建整个结构（仅在结构变化时重建）
            var needRebuild = false;
            // 思考内容首次出现
            if (msg.reasoning_content && !thinkingBody) needRebuild = true;
            // 回答内容首次出现
            if (msg.content && !answerEl) needRebuild = true;
            // 思考结束状态切换（正在思考→深度思考·Xs，需要折叠）
            if (thinkingBody && msg.thinkingTime && !bubble.querySelector('.thinking-block.collapsed')) needRebuild = true;

            if (needRebuild) {
                // 结构变化，需要重建
                var savedScrollTop = thinkingBody ? thinkingBody.scrollTop : 0;
                bubble.innerHTML = renderMsgContent(msg);
                bubble.setAttribute('data-raw', msg.content || '');
                // 恢复思考区域滚动位置
                thinkingBody = bubble.querySelector('.thinking-body');
                if (thinkingBody && savedScrollTop > 0) {
                    thinkingBody.scrollTop = savedScrollTop;
                }
            } else {
                // 增量更新，保留滚动位置（解决思考内容输出时滚动被重置的问题）
                if (msg.reasoning_content && thinkingBody) {
                    // 判断用户是否在底部附近（自动滚动策略）
                    var isNearBottom = thinkingBody.scrollHeight - thinkingBody.scrollTop - thinkingBody.clientHeight < 50;
                    thinkingBody.innerHTML = renderMarkdown(msg.reasoning_content);
                    // 如果用户在底部附近，自动滚动到新内容；否则保留用户滚动位置
                    if (isNearBottom) {
                        thinkingBody.scrollTop = thinkingBody.scrollHeight;
                    }
                }
                if (msg.content && answerEl) {
                    answerEl.innerHTML = renderMarkdown(msg.content);
                    bubble.setAttribute('data-raw', msg.content || '');
                }
            }
        }
    }
    // 流式输出期间实时渲染mermaid（带防抖，避免频繁渲染）
    // mermaid代码块在流式输出中可能不完整，渲染失败会自动保留原始文本
    _scheduleStreamingMermaidRender(streamEl);
    // 智能自动滚动：DOM 内容更新后立即同步滚动（无 rAF 延迟），
    // 使"内容增长"与"视图跟随"在同一帧完成，避免底部先被撑开再弹回的闪烁
    if (typeof ScrollFollowManager !== 'undefined') {
        ScrollFollowManager.syncScrollToBottom();
    } else {
        var isNearBottom = container.scrollHeight - container.scrollTop - container.clientHeight < 80;
        if (isNearBottom) {
            container.scrollTop = container.scrollHeight;
        }
    }
}

// 流式输出期间mermaid实时渲染的防抖定时器
var _streamingMermaidTimer = null;

// 调度流式输出期间的mermaid渲染（防抖300ms）
function _scheduleStreamingMermaidRender(container) {
    if (_streamingMermaidTimer) clearTimeout(_streamingMermaidTimer);
    _streamingMermaidTimer = setTimeout(function() {
        _streamingMermaidTimer = null;
        // 检查容器中是否有未渲染的mermaid元素
        if (!container || !container.isConnected) return;
        var unrendered = container.querySelectorAll('.mermaid:not([data-processed])');
        if (unrendered.length === 0) return;
        // 实时渲染（不跳过）
        processSpecialContent(container, false);
    }, 300);
}

// 创建assistant消息的footer（包含模型、token消耗、复制按钮）
function createAssistantFooter(msg) {
    var footer = document.createElement('div');
    footer.className = 'msg-footer';
    var copyIconSvg = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>';
    if (msg.modelName) {
        var modelSpan = document.createElement('span');
        modelSpan.className = 'msg-model-name';
        modelSpan.textContent = msg.modelName;
        footer.appendChild(modelSpan);
    }
    // token消耗显示（仅显示输出token，标记为"Token"）
    if (msg.completionTokens) {
        var outputToken = document.createElement('span');
        outputToken.className = 'msg-token-info';
        outputToken.textContent = 'Token≈' + fmtToken(msg.completionTokens);
        footer.appendChild(outputToken);
    }
    var copyBtn = document.createElement('button');
    copyBtn.className = 'footer-copy-btn';
    copyBtn.innerHTML = copyIconSvg;
    copyBtn.title = '复制';
    copyBtn.onclick = function() { copyMsgContent(this); };
    footer.appendChild(copyBtn);
    return footer;
}

// 创建流式输出期间的footer（包含模型、token计算中动效、复制按钮）
function createStreamingFooter(msg) {
    var footer = document.createElement('div');
    footer.className = 'msg-footer';
    var copyIconSvg = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>';
    if (msg.modelName) {
        var modelSpan = document.createElement('span');
        modelSpan.className = 'msg-model-name';
        modelSpan.textContent = msg.modelName;
        footer.appendChild(modelSpan);
    }
    // token计算中动效
    var tokenLoading = document.createElement('span');
    tokenLoading.className = 'msg-token-info msg-token-loading';
    tokenLoading.innerHTML = 'Token计算中<span class="token-wave"><span></span><span></span><span></span></span>';
    footer.appendChild(tokenLoading);
    var copyBtn = document.createElement('button');
    copyBtn.className = 'footer-copy-btn';
    copyBtn.innerHTML = copyIconSvg;
    copyBtn.title = '复制';
    copyBtn.onclick = function() { copyMsgContent(this); };
    footer.appendChild(copyBtn);
    return footer;
}

function updateAvatarIcons() {
    document.querySelectorAll('.avatar-icon').forEach(function(img) {
        var src = img.getAttribute('src') || '';
        if (src.indexOf('user') >= 0) {
            img.src = getAvatarIconSrc('user');
        } else if (src.indexOf('AIBot') >= 0) {
            img.src = getAvatarIconSrc('AIBot');
        }
    });
    var sidebarImg = document.getElementById('sidebarUserAvatar');
    if (sidebarImg) sidebarImg.src = getAvatarIconSrc('user');
    var thinkImg = document.getElementById('thinkIconImg');
    if (thinkImg) thinkImg.src = getAvatarIconSrc('icon_深度思考');
    var brandIcon = document.getElementById('brandIcon');
    if (brandIcon) brandIcon.src = getAvatarIconSrc('AIBot');
}

function getAvatarIconSrc(name) {
    var theme = (typeof getTheme === 'function') ? getTheme() : 'dark';
    var base = theme === 'dark' ? name + '_ss.svg' : name + '.svg';
    return '/icons/' + base;
}

function createMessageEl(msg) {
    var wrapper = document.createElement('div');
    wrapper.className = 'msg-wrapper ' + msg.role;

    var row = document.createElement('div');
    row.className = 'msg-row';

    // 头像
    var avatar = document.createElement('div');
    avatar.className = 'msg-avatar ' + (msg.role === 'user' ? 'user-av' : 'ai-av');
    avatar.innerHTML = msg.role === 'user'
        ? '<img class="avatar-icon" src="' + getAvatarIconSrc('user') + '" style="width:100%;height:100%;border-radius:10px;object-fit:cover" />'
        : '<img class="avatar-icon" src="' + getAvatarIconSrc('AIBot') + '" style="width:100%;height:100%;border-radius:10px;object-fit:cover" />';

    // 气泡
    var bubble = document.createElement('div');
    bubble.className = 'msg-bubble';
    bubble.setAttribute('data-raw', msg.content || '');

    if (msg.role === 'user') {
        var userHtml = escapeHtml(msg.content).replace(/\n/g, '<br>');
        // 用户消息中显示已发送的图片
        if (msg.images && msg.images.length > 0) {
            var imgsHtml = '';
            msg.images.forEach(function(base64) {
                imgsHtml += '<img class="user-msg-img" src="' + base64 + '" alt="发送的图片" onclick="openImageLightbox(this.src)" />';
            });
            userHtml = imgsHtml + userHtml;
        }
        bubble.innerHTML = userHtml;
    } else {
        bubble.innerHTML = renderMsgContent(msg);
    }

    // 时间显示在气泡上方
    if (msg.time) {
        var timeTop = document.createElement('div');
        timeTop.className = 'msg-time-top';
        timeTop.textContent = msg.time;
        wrapper.appendChild(timeTop);
    }

    row.appendChild(avatar);
    row.appendChild(bubble);
    wrapper.appendChild(row);

    // 底部信息栏
    var footer;
    if (msg.role === 'assistant') {
        footer = createAssistantFooter(msg);
    } else {
        footer = document.createElement('div');
        footer.className = 'msg-footer';
        var copyIconSvg = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>';
        var copyBtn = document.createElement('button');
        copyBtn.className = 'footer-copy-btn';
        copyBtn.innerHTML = copyIconSvg;
        copyBtn.title = '复制';
        copyBtn.onclick = function() { copyMsgContent(this); };
        footer.appendChild(copyBtn);
        // 用户端显示token
        if (msg.promptTokens) {
            var inputToken = document.createElement('span');
            inputToken.className = 'msg-token-info';
            inputToken.textContent = 'Token≈' + fmtToken(msg.promptTokens);
            footer.appendChild(inputToken);
        }
    }

    wrapper.appendChild(footer);

    return wrapper;
}

function renderMsgContent(msg) {
    var html = '';
    if (msg.reasoning_content) {
        var thinkStatus = msg.interrupted ? '思考被中断'
            : (msg.thinkingTime ? '深度思考 · ' + msg.thinkingTime + 's' : '正在思考...');
        html += '<div class="thinking-block' + (msg.thinkingTime ? ' collapsed' : '') + '">' +
            '<div class="thinking-header" onclick="this.parentElement.classList.toggle(\'collapsed\')">' +
            '<span class="arrow">▼</span> ' + thinkStatus + '</div>' +
            '<div class="thinking-body">' + renderMarkdown(msg.reasoning_content) + '</div></div>';
    }
    if (msg.content) {
        html += '<div class="answer-content">' + renderMarkdown(msg.content) + '</div>';
    }
    return html;
}

function renderMarkdown(text) {
    if (!text) return '';
    // ===== 处理流式输出中的mermaid块（未闭合的```mermaid）=====
    // 检测最后一个未闭合的 ```mermaid 块，让页面在代码输出过程中就能边渲染边显示图表
    // 注意：必须先处理流式块，避免被下面已闭合mermaid的regex误处理
    var streamingMermaidBlocks = [];
    text = text.replace(/(```mermaid\n[\s\S]*?)(```|$)/g, function(match, code, end) {
        if (end === '```') {
            // 已闭合，留给下面已闭合的mermaid处理逻辑
            return match;
        }
        // 未闭合（流式输出中），提取出来用流式模板渲染
        streamingMermaidBlocks.push(code);
        return '%%STREAMING_MERMAID_' + (streamingMermaidBlocks.length - 1) + '%%';
    });

    // 处理mermaid代码块 - 先替换为占位符
    var mermaidBlocks = [];
    text = text.replace(/```mermaid\n([\s\S]*?)```/g, function(match, code) {
        var idx = mermaidBlocks.length;
        mermaidBlocks.push(code.trim());
        return '%%MERMAID_' + idx + '%%';
    });

    var html = (typeof marked !== 'undefined' && marked.parse) ? marked.parse(text) : escapeHtml(text).replace(/\n/g, '<br>');

    // 还原mermaid - 调用 MermaidRenderer 模块生成卡片 HTML
    mermaidBlocks.forEach(function(code, idx) {
        var mermaidHtml = (typeof MermaidRenderer !== 'undefined' && MermaidRenderer.renderMermaidBlockTemplate)
            ? MermaidRenderer.renderMermaidBlockTemplate(code)
            : '<pre class="mermaid">' + escapeHtml(code) + '</pre>';
        html = html.replace('%%MERMAID_' + idx + '%%', mermaidHtml);
    });

    // 还原流式mermaid - 调用 MermaidRenderer 模块生成流式卡片 HTML（带"流式生成中"提示）
    streamingMermaidBlocks.forEach(function(code, idx) {
        var streamingHtml = (typeof MermaidRenderer !== 'undefined' && MermaidRenderer.renderStreamingMermaidBlockTemplate)
            ? MermaidRenderer.renderStreamingMermaidBlockTemplate(code)
            : '<pre class="mermaid">' + escapeHtml(code) + '</pre>';
        html = html.replace('%%STREAMING_MERMAID_' + idx + '%%', streamingHtml);
    });

    return html;
}

function copyMsgContent(btn) {
    var wrapper = btn.closest('.msg-wrapper');
    var bubble = wrapper.querySelector('.msg-bubble');
    // 优先使用data-raw属性（原始markdown内容）
    var rawText = bubble.getAttribute('data-raw') || '';
    if (!rawText) {
        rawText = bubble.innerText.trim();
    }
    navigator.clipboard.writeText(rawText).then(function() {
        btn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#6366f1" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>';
        setTimeout(function() {
            btn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>';
        }, 2000);
    }).catch(function() { showToast('复制失败'); });
}

// ===== Mermaid 相关实现已迁移到 my_js/mermaid-renderer.js =====
// 工具栏交互: 通过事件委托, 见 MermaidRenderer.handleToolbarAction
// 主题切换: MermaidRenderer.cycleTheme / applyTheme
// 全屏查看: MermaidRenderer.openFullscreen
// 代码预处理: MermaidRenderer.normalizeMermaidCode
// 渲染队列: MermaidRenderer._renderQueue / drainQueue
// processSpecialContent 中通过 MermaidRenderer.processContainer 调用

function processSpecialContent(container, skipMermaid) {
    // 表格包装处理 - 将table包裹在可水平滚动的.table-wrapper中
    container.querySelectorAll('.msg-bubble table').forEach(function(table) {
        if (table.parentElement.classList.contains('table-wrapper')) return;
        var wrapper = document.createElement('div');
        wrapper.className = 'table-wrapper';
        table.parentNode.insertBefore(wrapper, table);
        wrapper.appendChild(table);
        // 检测是否有溢出，添加提示阴影
        if (table.offsetWidth > wrapper.offsetWidth) {
            wrapper.classList.add('has-overflow');
        }
        // 监听滚动，滚动到底部时隐藏右侧阴影
        wrapper.addEventListener('scroll', function() {
            var isScrolledToEnd = this.scrollLeft + this.clientWidth >= this.scrollWidth - 2;
            this.classList.toggle('has-overflow', !isScrolledToEnd);
        });
    });

    // 代码块处理（安全检查：hljs可能未加载）
    container.querySelectorAll('pre code').forEach(function(block) {
        if (block.dataset.processed) return;
        block.dataset.processed = 'true';
        // 跳过mermaid代码块（hljs不支持mermaid语言）
        var lang = (block.className.match(/language-(\w+)/) || ['', ''])[1];
        if (lang === 'mermaid') {
            // mermaid代码块不交给hljs处理，但仍添加header
            if (!block.parentElement.querySelector('.code-header')) {
                var header = document.createElement('div');
                header.className = 'code-header';
                header.innerHTML = '<span>mermaid</span><button class="code-copy-btn" onclick="copyCode(this)">复制代码</button>';
                block.parentElement.insertBefore(header, block.parentElement.firstChild);
            }
            return;
        }
        if (typeof hljs !== 'undefined' && hljs.highlightElement) {
            hljs.highlightElement(block);
        }
        var pre = block.parentElement;
        if (pre.querySelector('.code-header')) return;
        if (!lang) lang = 'text';
        var header = document.createElement('div');
        header.className = 'code-header';
        header.innerHTML = '<span>' + lang + '</span><button class="code-copy-btn" onclick="copyCode(this)">复制代码</button>';
        pre.insertBefore(header, pre.firstChild);
    });

    // Mermaid渲染委托给 MermaidRenderer 模块（独立 my_js/mermaid-renderer.js）
    if (typeof MermaidRenderer !== 'undefined' && MermaidRenderer.processContainer) {
        MermaidRenderer.processContainer(container, skipMermaid);
    }

    // AI生成的图片增强：添加预览/放大/下载功能
    container.querySelectorAll('.msg-bubble img:not(.user-msg-img):not([data-img-enhanced])').forEach(function(img) {
        img.setAttribute('data-img-enhanced', 'true');
        img.style.cursor = 'pointer';
        img.onclick = function() { openImageLightbox(this.src); };
        // 添加图片工具栏
        var wrapper = document.createElement('div');
        wrapper.className = 'ai-image-wrapper';
        img.parentNode.insertBefore(wrapper, img);
        wrapper.appendChild(img);
        var toolbar = document.createElement('div');
        toolbar.className = 'ai-image-toolbar';
        toolbar.innerHTML =
            '<button class="ai-img-btn" onclick="event.stopPropagation();openImageLightbox(this.closest(\'.ai-image-wrapper\').querySelector(\'img\').src)" title="放大预览">' +
            '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/><line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/></svg>' +
            '</button>' +
            '<button class="ai-img-btn" onclick="event.stopPropagation();downloadImage(this.closest(\'.ai-image-wrapper\').querySelector(\'img\').src)" title="下载">' +
            '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>' +
            '</button>';
        wrapper.appendChild(toolbar);
    });

    // AI生成的视频增强：添加下载按钮
    container.querySelectorAll('.msg-bubble video:not([data-video-enhanced])').forEach(function(video) {
        video.setAttribute('data-video-enhanced', 'true');
        video.setAttribute('controls', 'true');
        video.setAttribute('controlslist', 'nodownload');
        video.setAttribute('preload', 'metadata');
        // 添加视频工具栏
        var wrapper = document.createElement('div');
        wrapper.className = 'ai-video-wrapper';
        video.parentNode.insertBefore(wrapper, video);
        wrapper.appendChild(video);
        var toolbar = document.createElement('div');
        toolbar.className = 'ai-video-toolbar';
        toolbar.innerHTML =
            '<button class="ai-vid-btn" onclick="event.stopPropagation();downloadVideo(this.closest(\'.ai-video-wrapper\').querySelector(\'video\').src)" title="下载视频">' +
            '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>' +
            ' 下载' +
            '</button>';
        wrapper.appendChild(toolbar);
    });
}

function copyCode(btn) {
    var code = btn.closest('pre').querySelector('code');
    navigator.clipboard.writeText(code.textContent).then(function() {
        btn.textContent = '已复制!';
        setTimeout(function() { btn.textContent = '复制代码'; }, 2000);
    });
}

// ===== UI工具 =====
function showLoading() {
    var container = document.getElementById('chatContainer');
    var loading = document.createElement('div');
    loading.id = 'loading-indicator';
    loading.className = 'msg-wrapper assistant';
    loading.innerHTML = '<div class="msg-row"><div class="msg-avatar ai-av"><img class="avatar-icon" src="' + getAvatarIconSrc('AIBot') + '" style="width:100%;height:100%;border-radius:10px;object-fit:cover" /></div><div class="loading-dots"><span></span><span></span><span></span></div></div>';
    appendToChat(loading);
    // 用户发送消息后，强制跟随并滚到底部（用户操作优先级最高）
    if (typeof ScrollFollowManager !== 'undefined') {
        ScrollFollowManager.scrollToBottomImmediate();
    } else {
        container.scrollTop = container.scrollHeight;
    }
}

function hideLoading() {
    var el = document.getElementById('loading-indicator');
    if (el) el.remove();
}

function showToast(msg) {
    if (window._layer) {
        window._layer.msg(msg, { time: 2500, shade: 0.3 });
    } else {
        var t = document.getElementById('toast');
        if (t) {
            t.textContent = msg;
            t.classList.add('show');
            setTimeout(function() { t.classList.remove('show'); }, 2500);
        }
    }
}

// 遮罩延迟移除定时器：防止快速开合侧边栏时，旧的移除回调把刚激活的遮罩删掉
var overlayRemoveTimer = null;

// 切换侧边栏：移动端用 .open 控制抽屉式开合并联动遮罩，桌面端用 .collapsed 控制折叠并联动主内容边距
function toggleSidebar() {
    var sidebar = document.getElementById('sidebar');
    var isMobile = window.innerWidth <= 768;
    if (isMobile) {
        sidebar.classList.toggle('open');
        sidebar.classList.remove('collapsed');
        document.body.classList.remove('sidebar-collapsed');
        toggleOverlay(sidebar.classList.contains('open'));
    } else {
        sidebar.classList.toggle('collapsed');
        // 桌面端：同步主内容区域压缩/展开
        document.body.classList.toggle('sidebar-collapsed', sidebar.classList.contains('collapsed'));
    }
    // 侧边栏切换动画结束后重新定位scroll-nav
    setTimeout(updateScrollNav, 350);
}

// 显示/隐藏移动端遮罩层：关闭时延迟移除节点以等待动画，重入时先清除待执行的移除定时器，避免遮罩状态与侧边栏失步
function toggleOverlay(show) {
    var ov = document.getElementById('overlay');
    if (overlayRemoveTimer) { clearTimeout(overlayRemoveTimer); overlayRemoveTimer = null; }
    if (show) {
        if (!ov) {
            ov = document.createElement('div');
            ov.id = 'overlay';
            ov.className = 'overlay active';
            ov.onclick = function() { toggleSidebar(); };
            document.body.appendChild(ov);
        } else { ov.classList.add('active'); }
    } else if (ov) {
        ov.classList.remove('active');
        overlayRemoveTimer = setTimeout(function() {
            if (ov.parentNode) ov.remove();
            overlayRemoveTimer = null;
        }, 300);
    }
}

function handleResize() {
    var sidebar = document.getElementById('sidebar');
    if (window.innerWidth > 768) {
        sidebar.classList.remove('open');
        toggleOverlay(false);
        // 桌面端：按 collapsed 状态同步主内容边距
        document.body.classList.toggle('sidebar-collapsed', sidebar.classList.contains('collapsed'));
    } else {
        // 移动端：主内容始终撑满
        document.body.classList.remove('sidebar-collapsed');
    }
    updateScrollNav(); // 窗口大小变化时重新定位滚动导航按钮
}

// ===== 滚动导航 =====
function appendToChat(el) {
    var container = document.getElementById('chatContainer');
    container.appendChild(el);
}
function scrollToTop() {
    // 用户主动操作：回到顶部 → 中断自动跟随
    if (typeof ScrollFollowManager !== 'undefined') {
        ScrollFollowManager.userScrollToTop();
    } else {
        var container = document.getElementById('chatContainer');
        if (container) container.scrollTo({ top: 0, behavior: 'smooth' });
    }
}

function scrollToBottom() {
    // 用户主动操作：回到底部/跳至最新 → 恢复自动跟随
    if (typeof ScrollFollowManager !== 'undefined') {
        ScrollFollowManager.userScrollToBottom();
    } else {
        var container = document.getElementById('chatContainer');
        if (container) container.scrollTo({ top: container.scrollHeight, behavior: 'smooth' });
    }
}

function updateScrollNav() {
    // 委托给 ScrollFollowManager 统一更新导航按钮显隐与定位
    if (typeof ScrollFollowManager !== 'undefined') {
        ScrollFollowManager.updateNavButtons();
        return;
    }
    var container = document.getElementById('chatContainer');
    var topBtn = document.getElementById('scrollToTopBtn');
    var bottomBtn = document.getElementById('scrollToBottomBtn');
    var scrollNav = document.getElementById('scrollNav');
    if (!container || !bottomBtn) return;

    // 动态定位scroll-nav到chat-container右下角（fixed定位）
    if (scrollNav) {
        var rect = container.getBoundingClientRect();
        scrollNav.style.position = 'fixed';
        scrollNav.style.right = (window.innerWidth - rect.right + 16) + 'px';
        scrollNav.style.bottom = (window.innerHeight - rect.bottom + 12) + 'px';
    }

    var scrollTop = container.scrollTop;
    var scrollHeight = container.scrollHeight;
    var clientHeight = container.clientHeight;
    var isAtTop = scrollTop <= 5;
    var isAtBottom = scrollHeight - scrollTop - clientHeight <= 5;

    if (topBtn) topBtn.style.display = isAtTop ? 'none' : 'flex';
    bottomBtn.style.display = isAtBottom ? 'none' : 'flex';
}

function autoResize() {
    var ta = document.getElementById('userInput');
    ta.style.height = 'auto';
    ta.style.height = Math.min(ta.scrollHeight, 150) + 'px';
}

// ===== 图片粘贴处理（多模态支持） =====
function handlePasteImage(e) {
    var items = (e.clipboardData || e.originalEvent.clipboardData).items;
    if (!items) return;
    var imageItems = [];
    for (var i = 0; i < items.length; i++) {
        if (items[i].type.indexOf('image') !== -1) {
            imageItems.push(items[i]);
        }
    }
    if (imageItems.length === 0) return;
    e.preventDefault();

    var supportsMultimodal = window._currentModelSupportsMultimodal;
    imageItems.forEach(function(item) {
        var file = item.getAsFile();
        if (!file) return;
        if (file.size > MAX_IMAGE_SIZE) {
            showToast('图片超过5MB限制');
            return;
        }
        var reader = new FileReader();
        reader.onload = function(event) {
            var base64 = event.target.result;
            if (!supportsMultimodal) {
                // 不支持多模态：显示警告
                showPasteWarning('当前模型不支持图像理解，请切换支持多模态的模型或删除图片');
            }
            pendingImages.push(base64);
            renderImagePreview();
        };
        reader.readAsDataURL(file);
    });
}

// ===== 图片上传按钮（显式上传入口） =====
function triggerImageUpload() {
    var input = document.getElementById('imageFileInput');
    if (input) {
        input.value = ''; // 清空以便重复选择同一文件
        input.click();
    }
}

function handleImageUpload(input) {
    var files = input.files;
    if (!files || files.length === 0) return;
    var supportsMultimodal = window._currentModelSupportsMultimodal;
    Array.prototype.forEach.call(files, function(file) {
        if (file.type.indexOf('image') === -1) return;
        if (file.size > MAX_IMAGE_SIZE) {
            showToast('图片超过5MB限制');
            return;
        }
        var reader = new FileReader();
        reader.onload = function(event) {
            var base64 = event.target.result;
            if (!supportsMultimodal) {
                showPasteWarning('当前模型不支持图像理解，请切换支持多模态的模型或删除图片');
            }
            pendingImages.push(base64);
            renderImagePreview();
        };
        reader.readAsDataURL(file);
    });
}

function showPasteWarning(msg) {
    var warning = document.getElementById('pasteWarning');
    if (!warning) return;
    warning.textContent = msg;
    warning.style.display = 'block';
}

function hidePasteWarning() {
    var warning = document.getElementById('pasteWarning');
    if (warning) warning.style.display = 'none';
}

function renderImagePreview() {
    var area = document.getElementById('imagePreviewArea');
    if (!area) return;
    if (pendingImages.length === 0) {
        area.style.display = 'none';
        area.innerHTML = '';
        // 如果没有图片了且警告显示中，隐藏警告
        hidePasteWarning();
        return;
    }
    area.style.display = 'flex';
    area.innerHTML = '';
    pendingImages.forEach(function(base64, idx) {
        var item = document.createElement('div');
        item.className = 'image-preview-item';
        item.innerHTML = '<img src="' + base64 + '" alt="粘贴图片">' +
            '<button class="image-preview-remove" data-idx="' + idx + '" title="删除">' +
            '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>' +
            '</button>';
        item.querySelector('.image-preview-remove').onclick = function() {
            pendingImages.splice(idx, 1);
            // 删除图片后，如果警告显示中，隐藏警告
            if (pendingImages.length === 0) hidePasteWarning();
            renderImagePreview();
        };
        area.appendChild(item);
    });
}

function clearPendingImages() {
    pendingImages = [];
    renderImagePreview();
    hidePasteWarning();
}

function escapeHtml(str) {
    var div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}

function nowStr() {
    return new Date().toLocaleString('zh-CN');
}

// ===== 图片灯箱（预览/放大/缩小/下载） =====
function openImageLightbox(src) {
    var overlay = document.createElement('div');
    overlay.className = 'image-lightbox-overlay';

    var scale = 1;
    var translateX = 0;
    var translateY = 0;

    // 工具栏
    var toolbar = document.createElement('div');
    toolbar.className = 'image-lightbox-toolbar';
    toolbar.innerHTML =
        '<button class="lightbox-btn" id="lbZoomIn" title="放大">' +
        '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/><line x1="11" y1="8" x2="11" y2="14"/><line x1="8" y1="11" x2="14" y2="11"/></svg>' +
        '</button>' +
        '<button class="lightbox-btn" id="lbZoomOut" title="缩小">' +
        '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/><line x1="8" y1="11" x2="14" y2="11"/></svg>' +
        '</button>' +
        '<button class="lightbox-btn" id="lbZoomReset" title="重置">1:1</button>' +
        '<span class="lightbox-sep"></span>' +
        '<button class="lightbox-btn" id="lbDownload" title="下载">' +
        '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>' +
        '</button>';

    // 关闭按钮
    var closeBtn = document.createElement('button');
    closeBtn.className = 'image-lightbox-close';
    closeBtn.innerHTML = '✕';

    // 图片容器
    var imgContainer = document.createElement('div');
    imgContainer.className = 'image-lightbox-content';
    var img = document.createElement('img');
    img.src = src;
    img.className = 'image-lightbox-img';
    imgContainer.appendChild(img);

    function updateTransform() {
        img.style.transform = 'translate(' + translateX + 'px,' + translateY + 'px) scale(' + scale + ')';
    }

    // 工具栏事件
    toolbar.querySelector('#lbZoomIn').onclick = function() { scale = Math.min(scale + 0.25, 5); updateTransform(); };
    toolbar.querySelector('#lbZoomOut').onclick = function() { scale = Math.max(scale - 0.25, 0.25); updateTransform(); };
    toolbar.querySelector('#lbZoomReset').onclick = function() { scale = 1; translateX = 0; translateY = 0; updateTransform(); };
    toolbar.querySelector('#lbDownload').onclick = function() { downloadImage(src); };

    // 滚轮缩放
    imgContainer.addEventListener('wheel', function(e) {
        e.preventDefault();
        var delta = e.deltaY > 0 ? -0.15 : 0.15;
        scale = Math.max(0.25, Math.min(scale + delta, 5));
        updateTransform();
    }, { passive: false });

    // 拖动
    var isDragging = false, startX = 0, startY = 0, startTX = 0, startTY = 0;
    imgContainer.addEventListener('mousedown', function(e) {
        isDragging = true; startX = e.clientX; startY = e.clientY;
        startTX = translateX; startTY = translateY;
        imgContainer.style.cursor = 'grabbing';
    });
    document.addEventListener('mousemove', function(e) {
        if (!isDragging) return;
        translateX = startTX + (e.clientX - startX);
        translateY = startTY + (e.clientY - startY);
        updateTransform();
    });
    document.addEventListener('mouseup', function() {
        isDragging = false;
        if (imgContainer) imgContainer.style.cursor = 'grab';
    });
    imgContainer.style.cursor = 'grab';

    // 关闭
    function closeLightbox() {
        overlay.remove();
        document.removeEventListener('keydown', escHandler);
    }
    var escHandler = function(e) { if (e.key === 'Escape') closeLightbox(); };
    document.addEventListener('keydown', escHandler);
    closeBtn.onclick = closeLightbox;
    overlay.onclick = function(e) { if (e.target === overlay) closeLightbox(); };

    overlay.appendChild(closeBtn);
    overlay.appendChild(toolbar);
    overlay.appendChild(imgContainer);
    document.body.appendChild(overlay);
}

function downloadImage(src) {
    var a = document.createElement('a');
    a.href = src;
    a.download = 'image-' + Date.now() + '.png';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    showToast('已开始下载');
}

function downloadVideo(src) {
    var a = document.createElement('a');
    a.href = src;
    a.download = 'video-' + Date.now() + '.mp4';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    showToast('已开始下载');
}

// Init sidebar avatar on page load (theme-aware)
(function() {
    var sidebarImg = document.getElementById('sidebarUserAvatar');
    if (sidebarImg) sidebarImg.src = getAvatarIconSrc('user');
    var thinkImg = document.getElementById('thinkIconImg');
    if (thinkImg) thinkImg.src = getAvatarIconSrc('icon_深度思考');
    var brandIcon = document.getElementById('brandIcon');
    if (brandIcon) brandIcon.src = getAvatarIconSrc('AIBot');
})();

// ===== 用户头像菜单（设置/关于） =====
function toggleUserMenu(e) {
    if (e) e.stopPropagation();
    var menu = document.getElementById('userMenu');
    if (!menu) return;
    // 如果点击源自菜单内部（菜单项），不切换显隐
    if (e && e.target && menu.contains(e.target)) return;
    if (menu.style.display === 'block') {
        menu.style.display = 'none';
    } else {
        menu.style.display = 'block';
        // 点击外部关闭
        setTimeout(function() {
            document.addEventListener('click', closeUserMenuOnOutside);
        }, 0);
    }
}

function closeUserMenuOnOutside(e) {
    var menu = document.getElementById('userMenu');
    var userInfo = document.getElementById('userInfo');
    if (!menu) return;
    if (userInfo && userInfo.contains(e.target)) return;
    if (menu.contains(e.target)) return;
    menu.style.display = 'none';
    document.removeEventListener('click', closeUserMenuOnOutside);
}

// ===== 关于弹窗 =====
function openAboutModal() {
    var menu = document.getElementById('userMenu');
    if (menu) menu.style.display = 'none';
    var ver = (typeof APP_VERSION !== 'undefined') ? APP_VERSION : '';
    var logoSrc = getAvatarIconSrc('AIBot');
    var html = '<div class="about-modal-content">' +
        '<div class="about-logo"><img src="' + logoSrc + '" alt="AI Chat" /></div>' +
        '<div class="about-title">AI Chat Platform</div>' +
        '<div class="about-version">软件版本号：v' + escapeHtml(ver) + '</div>' +
        '<div class="about-desc">一个简洁高效的多模型 AI 聊天平台</div>' +
        '</div>';
    if (window._layer) {
        var isMobile = window.innerWidth <= 768;
        var aboutWidth = isMobile ? '90%' : '380px';
        window._layer.open({
            type: 1,
            title: '关于',
            area: [aboutWidth, 'auto'],
            shadeClose: true,
            content: html
        });
    } else {
        alert('AI Chat Platform\n版本：v' + ver);
    }
}

// ===== 设置弹窗（带侧边栏） =====
var _settingsLayerIndex = null;
var _currentSettingsTab = 'changePassword';

function openSettingsModal() {
    var menu = document.getElementById('userMenu');
    if (menu) menu.style.display = 'none';
    if (!window._layer) { showToast('UI组件未加载完成'); return; }

    // 防止浏览器密码自动填充时把用户名灌入会话搜索框
    var searchInput = document.getElementById('chatSearchInput');
    var savedSearchValue = searchInput ? searchInput.value : '';
    // 在弹窗打开后短时间内多次还原搜索框值，抵消浏览器 autofill
    var restoreTimers = [];
    function scheduleRestore() {
        [0, 30, 100, 300, 600, 1000].forEach(function(ms) {
            restoreTimers.push(setTimeout(function() {
                if (searchInput && searchInput.value !== savedSearchValue) {
                    searchInput.value = savedSearchValue;
                }
            }, ms));
        });
    }
    scheduleRestore();

    var content = buildSettingsModalHtml();
    // 移动端使用响应式尺寸，避免弹窗超出视口
    var isMobile = window.innerWidth <= 768;
    var areaWidth = isMobile ? '95%' : '720px';
    var areaHeight = isMobile ? '85%' : '480px';
    _settingsLayerIndex = window._layer.open({
        type: 1,
        title: '设置',
        area: [areaWidth, areaHeight],
        shadeClose: false,
        maxmin: false,
        content: content,
        success: function(layero, index) {
            // 移除layui-layer-content默认padding，让侧边栏贴边
            try {
                var contentEl = layero.find('.layui-layer-content')[0];
                if (contentEl) {
                    contentEl.style.padding = '0';
                    contentEl.style.overflow = 'hidden';
                }
                // 移动端：确保弹窗不超出视口
                if (isMobile) {
                    layero.css({
                        'max-width': '100vw',
                        'max-height': '100vh'
                    });
                }
            } catch(e) {}
            // 默认选中第一个菜单项
            switchSettingsTab('changePassword');
            // 再次保险还原搜索框值（在表单渲染后）
            scheduleRestore();
        },
        end: function() {
            _settingsLayerIndex = null;
            // 清理定时器
            restoreTimers.forEach(function(t) { clearTimeout(t); });
            restoreTimers = [];
        }
    });
}

function buildSettingsModalHtml() {
    return '' +
        '<div class="settings-modal">' +
        '  <div class="settings-sidebar">' +
        '    <div class="settings-menu-item active" data-tab="changePassword" onclick="switchSettingsTab(\'changePassword\')">' +
        '      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>' +
        '      <span>修改密码</span>' +
        '    </div>' +
        '    <div class="settings-menu-item" data-tab="systemPrompt" onclick="switchSettingsTab(\'systemPrompt\')">' +
        '      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 7V4h16v3"/><path d="M9 20h6"/><path d="M12 4v16"/></svg>' +
        '      <span>全局提示词</span>' +
        '    </div>' +
        '    <div class="settings-menu-item" data-tab="dataManagement" onclick="switchSettingsTab(\'dataManagement\')">' +
        '      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/></svg>' +
        '      <span>数据管理</span>' +
        '    </div>' +
        '  </div>' +
        '  <div class="settings-content" id="settingsContent">' +
        '    ' + buildChangePasswordPanel() +
        '  </div>' +
        '</div>';
}

function buildChangePasswordPanel() {
    return '' +
        '<div class="settings-panel" id="panel-changePassword">' +
        '  <h3 class="settings-panel-title">修改密码</h3>' +
        '  <form autocomplete="off" onsubmit="return false;">' +
        // 防浏览器自动填充：放置一个隐藏的 username 和 password 占位字段，吸收浏览器的 autofill
        '  <input type="text" name="fakeusernameremembered" style="position:absolute;left:-9999px;width:1px;height:1px;opacity:0" tabindex="-1" autocomplete="username" />' +
        '  <input type="password" name="fakepasswordremembered" style="position:absolute;left:-9999px;width:1px;height:1px;opacity:0" tabindex="-1" autocomplete="new-password" />' +
        '  <div class="settings-form-group">' +
        '    <label>当前密码</label>' +
        '    <input type="password" id="cp_oldPwd" class="settings-input" placeholder="请输入当前密码" autocomplete="off" data-lpignore="true" data-form-type="other" />' +
        '  </div>' +
        '  <div class="settings-form-group">' +
        '    <label>新密码</label>' +
        '    <input type="password" id="cp_newPwd" class="settings-input" placeholder="请输入新密码（至少4位）" autocomplete="off" data-lpignore="true" data-form-type="other" />' +
        '  </div>' +
        '  <div class="settings-form-group">' +
        '    <label>确认新密码</label>' +
        '    <input type="password" id="cp_confirmPwd" class="settings-input" placeholder="请再次输入新密码" autocomplete="off" data-lpignore="true" data-form-type="other" />' +
        '    <div class="settings-form-tip" id="cp_tip"></div>' +
        '  </div>' +
        '  <div class="settings-form-actions">' +
        '    <button type="button" class="settings-btn settings-btn-primary" onclick="submitChangePassword()">提交</button>' +
        '  </div>' +
        '  <div class="settings-form-note">提交成功后将自动退出登录，请使用新密码重新登录。</div>' +
        '  </form>' +
        '</div>';
}

function switchSettingsTab(tab) {
    _currentSettingsTab = tab;
    // 更新菜单选中态
    var items = document.querySelectorAll('.settings-menu-item');
    items.forEach(function(it) {
        if (it.getAttribute('data-tab') === tab) it.classList.add('active');
        else it.classList.remove('active');
    });
    // 切换面板
    var content = document.getElementById('settingsContent');
    if (!content) return;
    if (tab === 'changePassword') {
        content.innerHTML = buildChangePasswordPanel();
    } else if (tab === 'systemPrompt') {
        content.innerHTML = buildSystemPromptPanel();
        // 渲染后拉取当前已保存的全局提示词进行回填
        loadSystemPrompt();
    } else if (tab === 'dataManagement') {
        content.innerHTML = buildDataManagementPanel();
    }
}

// 构建「全局提示词」设置面板
function buildSystemPromptPanel() {
    return '' +
        '<div class="settings-panel" id="panel-systemPrompt">' +
        '  <h3 class="settings-panel-title">全局提示词</h3>' +
        '  <div class="settings-form-group">' +
        '    <label>自定义全局提示词（System Prompt）</label>' +
        '    <textarea id="sp_content" class="settings-input settings-textarea" rows="8" ' +
        '       placeholder="例如：你是一名严谨的中文技术顾问，回答简洁、准确，并使用 Markdown 排版。留空则使用系统默认提示词。" ' +
        '       autocomplete="off" data-lpignore="true" data-form-type="other"></textarea>' +
        '    <div class="settings-form-tip" id="sp_tip"></div>' +
        '  </div>' +
        '  <div class="settings-form-actions">' +
        '    <button type="button" class="settings-btn settings-btn-primary" onclick="saveSystemPrompt()">保存</button>' +
        '  </div>' +
        '  <div class="settings-form-note">每次对话调用都会携带该提示词，并始终置于消息列表最前面（优先级最高，先于对话历史与当前输入）。对所有会话生效。</div>' +
        '</div>';
}

// 统计当前包含用户消息的有效会话数量（空白的"新会话"不计入）
function countValidChats() {
    var count = 0;
    Object.keys(chats).forEach(function(id) {
        var msgs = chats[id] || [];
        for (var i = 0; i < msgs.length; i++) {
            if (msgs[i].role === 'user') { count++; break; }
        }
    });
    return count;
}

// 构建「数据管理」设置面板（导出全部历史对话 / 删除全部对话）
function buildDataManagementPanel() {
    var count = countValidChats();
    return '' +
        '<div class="settings-panel" id="panel-dataManagement">' +
        '  <h3 class="settings-panel-title">数据管理</h3>' +
        '  <div class="data-mgmt-rows">' +
        '    <div class="data-mgmt-row">' +
        '      <div class="data-mgmt-icon">' +
        '        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>' +
        '      </div>' +
        '      <div class="data-mgmt-info">' +
        '        <div class="data-mgmt-title">导出全部历史对话</div>' +
        '        <div class="data-mgmt-desc">将当前账号下的全部会话记录（' + count + ' 条）导出为 TXT 文本文件</div>' +
        '      </div>' +
        '      <button type="button" class="settings-btn settings-btn-primary" onclick="confirmExportAllChats()">导出</button>' +
        '    </div>' +
        '    <div class="data-mgmt-row data-mgmt-row-danger">' +
        '      <div class="data-mgmt-icon data-mgmt-icon-danger">' +
        '        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><line x1="10" y1="11" x2="10" y2="17"/><line x1="14" y1="11" x2="14" y2="17"/></svg>' +
        '      </div>' +
        '      <div class="data-mgmt-info">' +
        '        <div class="data-mgmt-title">删除全部对话</div>' +
        '        <div class="data-mgmt-desc">清空当前账号下的全部聊天记录，删除后无法恢复</div>' +
        '      </div>' +
        '      <button type="button" class="settings-btn settings-btn-danger" onclick="confirmDeleteAllChats()">删除</button>' +
        '    </div>' +
        '  </div>' +
        '  <div class="settings-form-note">导出文件由浏览器保存到本地；删除操作会同步清除服务端数据，多端立即生效。</div>' +
        '</div>';
}

// 数据管理 - 导出全部历史对话：二次确认后复用现有导出逻辑
function confirmExportAllChats() {
    var count = countValidChats();
    if (count === 0) {
        showToast('当前没有可导出的会话');
        return;
    }
    showConfirmDialog('确定导出当前账号下的全部 ' + count + ' 条历史对话吗？', '导出确认', function() {
        exportChats();
    });
}

// 数据管理 - 删除全部对话：二次确认后清空本地与服务端全部会话数据，并刷新侧边栏会话列表
function confirmDeleteAllChats() {
    if (isStreamActive) {
        showToast('请等待回答完成');
        return;
    }
    var ids = Object.keys(chats);
    var count = countValidChats();
    if (count === 0) {
        showToast('当前没有可删除的会话');
        return;
    }
    showConfirmDialog('此操作将删除当前账号下全部 ' + count + ' 条会话及其聊天记录，删除后无法恢复。确定继续？', '删除全部对话', function() {
        // 记录全部被删除的会话ID，随同步请求一并提交服务端删除
        ids.forEach(function(id) {
            deletedChatIds.push(id);
        });
        // 清空本地全部会话数据
        chats = {};
        currentChatId = null;
        // 新建空白会话：内部会同步服务端、刷新侧边栏列表并重置视图
        newChat();
        // 重新渲染当前面板，刷新会话数量显示
        if (_currentSettingsTab === 'dataManagement') {
            var content = document.getElementById('settingsContent');
            if (content) content.innerHTML = buildDataManagementPanel();
        }
        showToast('已删除全部对话');
    });
}

// 拉取并回填当前用户已保存的全局提示词
function loadSystemPrompt() {
    var tipEl = document.getElementById('sp_tip');
    fetch('/api/user/system-prompt', {
        method: 'GET',
        headers: authHeaders()
    }).then(function(r) {
        if (r.status === 401) {
            showToast(authFailMsg(r));
            setTimeout(function() { doLogout(); }, 1000);
            return null;
        }
        return r.json();
    }).then(function(data) {
        if (!data) return;
        var el = document.getElementById('sp_content');
        if (el && data.success) {
            el.value = data.systemPrompt || '';
        } else if (el && data.message && tipEl) {
            tipEl.textContent = data.message;
            tipEl.classList.add('error');
        }
    }).catch(function(err) {
        if (tipEl) {
            tipEl.textContent = '加载失败：' + (err && err.message ? err.message : '网络异常');
            tipEl.classList.add('error');
        }
    });
}

// 保存当前用户的全局提示词
function saveSystemPrompt() {
    var el = document.getElementById('sp_content');
    var tipEl = document.getElementById('sp_tip');
    if (tipEl) { tipEl.textContent = ''; tipEl.classList.remove('error'); }
    var prompt = el ? el.value : '';
    if (prompt.length > 20000) {
        if (tipEl) { tipEl.textContent = '全局提示词过长（最多 20000 字符）'; tipEl.classList.add('error'); }
        return;
    }
    fetch('/api/user/system-prompt', {
        method: 'PUT',
        headers: authHeaders(),
        body: JSON.stringify({ systemPrompt: prompt })
    }).then(function(r) {
        if (r.status === 401) {
            showToast(authFailMsg(r));
            setTimeout(function() { doLogout(); }, 1000);
            return null;
        }
        return r.json();
    }).then(function(data) {
        if (!data) return;
        if (data.success) {
            showToast('全局提示词已保存');
            if (tipEl) { tipEl.textContent = '已保存，立即对所有新对话生效。'; }
        } else {
            if (tipEl) { tipEl.textContent = data.message || '保存失败'; tipEl.classList.add('error'); }
        }
    }).catch(function(err) {
        if (tipEl) {
            tipEl.textContent = '保存失败：' + (err && err.message ? err.message : '网络异常');
            tipEl.classList.add('error');
        }
    });
}

function submitChangePassword() {
    var oldPwd = (document.getElementById('cp_oldPwd') || {}).value || '';
    var newPwd = (document.getElementById('cp_newPwd') || {}).value || '';
    var confirmPwd = (document.getElementById('cp_confirmPwd') || {}).value || '';
    var tipEl = document.getElementById('cp_tip');
    if (tipEl) { tipEl.textContent = ''; tipEl.classList.remove('error'); }

    if (!oldPwd || !newPwd || !confirmPwd) {
        if (tipEl) { tipEl.textContent = '请完整填写所有密码字段'; tipEl.classList.add('error'); }
        return;
    }
    if (newPwd.length < 4) {
        if (tipEl) { tipEl.textContent = '新密码长度不能少于4位'; tipEl.classList.add('error'); }
        return;
    }
    if (newPwd !== confirmPwd) {
        if (tipEl) { tipEl.textContent = '两次输入的新密码不一致'; tipEl.classList.add('error'); }
        return;
    }
    if (oldPwd === newPwd) {
        if (tipEl) { tipEl.textContent = '新密码不能与当前密码相同'; tipEl.classList.add('error'); }
        return;
    }

    fetch('/api/auth/change-password', {
        method: 'POST',
        headers: authHeaders(),
        body: JSON.stringify({ oldPassword: oldPwd, newPassword: newPwd, confirmPassword: confirmPwd })
    }).then(function(r) {
        if (r.status === 401) {
            showToast(authFailMsg(r));
            setTimeout(function() { doLogout(); }, 1000);
            return null;
        }
        return r.json();
    }).then(function(data) {
        if (!data) return;
        if (data.success) {
            showToast('密码修改成功，即将退出登录');
            // 关闭设置弹窗
            if (_settingsLayerIndex !== null && window._layer) {
                window._layer.close(_settingsLayerIndex);
                _settingsLayerIndex = null;
            }
            setTimeout(function() {
                // 直接清理本地并跳转登录页（服务端token已失效）
                safeStorageRemove('token');
                safeStorageRemove('username');
                safeStorageRemove('role');
                window.location.href = '/login.html';
            }, 1200);
        } else {
            if (tipEl) { tipEl.textContent = data.message || '修改失败'; tipEl.classList.add('error'); }
        }
    }).catch(function(err) {
        if (tipEl) { tipEl.textContent = '请求失败：' + (err && err.message ? err.message : '网络异常'); tipEl.classList.add('error'); }
    });
}

