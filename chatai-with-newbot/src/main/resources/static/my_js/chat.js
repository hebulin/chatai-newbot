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
var CHATS_KEY = 'chats_' + CURRENT_USER;
var LAST_CHAT_KEY = 'lastChatId_' + CURRENT_USER;
var currentChatId = null;
var chats = {};
var models = [];
var currentModelId = '';
var currentModelName = '';
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

    // 监听深度思考开关
    form.on('switch(deepThink)', function(data) {
        // data.elem 是原生checkbox，data.value 是值，data.othis 是Layui渲染的switch DOM
        // 使用 data.othis 是否有 layui-form-onswitch 类来判断实际开关状态
        isDeepThinking = data.othis.hasClass('layui-form-onswitch');
        console.log('深度思考开关切换:', isDeepThinking);
    });

    // ===== 初始化 =====
    if (!TOKEN) { window.location.href = '/login.html'; return; }

    // 初始化marked（安全检查：CDN可能被浏览器扩展阻止加载）
    if (typeof marked !== 'undefined' && marked.setOptions) {
        marked.setOptions({
            gfm: true, breaks: true, headerIds: false, mangle: false,
            highlight: function(code, lang) {
                try {
                    if (typeof hljs !== 'undefined') {
                        if (lang && hljs.getLanguage(lang)) return hljs.highlight(code, {language: lang}).value;
                        return hljs.highlightAuto(code).value;
                    }
                } catch(e) {}
                return code;
            }
        });
    }
    if (typeof mermaid !== 'undefined' && mermaid.initialize) {
        mermaid.initialize({ startOnLoad: false, theme: 'default' });
    }

    // 显示用户信息
    document.getElementById('usernameDisplay').textContent = safeStorageGet('username') || '用户';
    if (safeStorageGet('role') === 'admin') {
        document.getElementById('adminBtn').style.display = 'flex';
    }

    // 加载会话（按用户隔离）
    try { chats = safeStorageParse(CHATS_KEY) || {}; } catch(e) { chats = {}; }
    var lastId = safeStorageGet(LAST_CHAT_KEY);
    if (lastId && chats[lastId]) { currentChatId = lastId; } else { newChat(); }

    loadModels();
    updateChatList();
    displayMessages();

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

    // 滚动导航监听
    var chatContainer = document.getElementById('chatContainer');
    if (chatContainer) {
        chatContainer.addEventListener('scroll', updateScrollNav);
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
            showToast('登录已过期，请重新登录');
            setTimeout(function() { window.location.href = '/login.html'; }, 1500);
            return;
        }
        return r.json();
    })
    .then(function(data) {
        if (!data || !data.success) return;
        models = data.data;
        if (models.length > 0) {
            currentModelId = models[0].id;
            currentModelName = models[0].displayName;
            updateModelSelectDisplay(models[0]);
            // 存储当前模型的多模态支持状态
            window._currentModelSupportsMultimodal = models[0].supportsMultimodal || false;
        }
        renderModelSelector(models);
        // 初始化深度思考状态（在renderModelSelector之后，确保DOM已就绪）
        if (models.length > 0) {
            isDeepThinking = models[0].supportsThinking;
            var deepThinkCheck = document.getElementById('deepThinkCheck');
            if (deepThinkCheck) deepThinkCheck.checked = models[0].supportsThinking;
            var thinkToggle = document.getElementById('thinkToggle');
            if (thinkToggle) thinkToggle.style.display = models[0].supportsThinking ? 'flex' : 'none';
            if (window._form) window._form.render('checkbox');
        }
    }).catch(function(e) { console.error('加载模型失败:', e); });
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

// ===== 自定义模型选择下拉框 =====
function renderModelSelector(modelsData) {
    var dropdown = document.getElementById('modelSelectDropdown');
    if (!dropdown) return;
    dropdown.innerHTML = '';

    // 按厂商分组
    var groups = {};
    var groupOrder = [];
    modelsData.forEach(function(m) {
        var key = m.providerName || m.providerId || 'custom';
        if (!groups[key]) {
            groups[key] = { name: key, providerId: m.providerId || inferProviderId(m), models: [] };
            groupOrder.push(key);
        }
        groups[key].models.push(m);
    });

    groupOrder.forEach(function(key) {
        var group = groups[key];
        // 厂商分组标题
        var groupHeader = document.createElement('div');
        groupHeader.className = 'model-group-header';
        var iconPath = providerIconMap[group.providerId];
        var iconHtml = '';
        if (iconPath) {
            iconHtml = '<img src="' + iconPath + '" class="model-group-icon" />';
        }
        groupHeader.innerHTML = iconHtml + '<span>' + escapeHtml(group.name) + '</span>';
        dropdown.appendChild(groupHeader);

        // 模型列表
        group.models.forEach(function(m) {
            var item = document.createElement('div');
            item.className = 'model-item' + (m.id === currentModelId ? ' selected' : '');
            item.setAttribute('data-model-id', m.id);
            item.setAttribute('data-provider-id', m.providerId || inferProviderId(m));
            item.setAttribute('data-supports-thinking', m.supportsThinking ? 'true' : 'false');
            var pid = m.providerId || inferProviderId(m);
            var itemIconPath = providerIconMap[pid];
            var itemIconHtml = '';
            if (itemIconPath) {
                itemIconHtml = '<img src="' + itemIconPath + '" class="model-item-icon" />';
            }
            item.innerHTML = itemIconHtml + '<span class="model-item-name">' + escapeHtml(m.displayName) + '</span>';
            item.onclick = function() {
                selectModel(m);
            };
            dropdown.appendChild(item);
        });
    });
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
    // 更新选中态
    var items = document.querySelectorAll('.model-item');
    items.forEach(function(item) {
        if (item.getAttribute('data-model-id') === model.id) {
            item.classList.add('selected');
        } else {
            item.classList.remove('selected');
        }
    });
}

function updateModelSelectDisplay(model) {
    var valueEl = document.getElementById('modelSelectValue');
    if (!valueEl) return;
    var pid = model.providerId || inferProviderId(model);
    var iconPath = providerIconMap[pid];
    var iconHtml = '';
    if (iconPath) {
        iconHtml = '<img src="' + iconPath + '" class="model-select-icon" />';
    }
    valueEl.innerHTML = iconHtml + '<span>' + escapeHtml(model.displayName) + '</span>';
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
            // 如果下方空间不足且上方空间更大，则向上展开
            if (spaceBelow < dropdownHeight + 10 && spaceAbove > spaceBelow) {
                dropdown.classList.add('dropup');
            } else {
                dropdown.classList.remove('dropup');
            }
            // 自动滚动到当前选中项
            var selectedItem = dropdown.querySelector('.model-item.selected');
            if (selectedItem) {
                selectedItem.scrollIntoView({ block: 'nearest' });
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
        modelDropdownOpen = false;
    }
    if (trigger) {
        trigger.classList.remove('active');
    }
}

// 更新深度思考开关
// isModelChanged: 是否是切换了模型（true=模型变了，默认开启思考；false=同模型，保持当前状态）
function updateThinkToggle(model, isModelChanged) {
    var thinkToggle = document.getElementById('thinkToggle');
    var deepThinkCheck = document.getElementById('deepThinkCheck');
    if (model.supportsThinking) {
        thinkToggle.style.display = 'flex';
        if (isModelChanged) {
            // 切换到新模型时，默认开启深度思考
            isDeepThinking = true;
            deepThinkCheck.checked = true;
        }
        // 如果不是模型切换（如初始化），保持当前 isDeepThinking 状态
    } else {
        thinkToggle.style.display = 'none';
        isDeepThinking = false;
        deepThinkCheck.checked = false;
    }
    // 重新渲染 Layui switch
    if (window._form) {
        window._form.render('checkbox');
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
    safeStorageSet(LAST_CHAT_KEY, currentChatId);
    saveChats();
    updateChatList();
    displayMessages();
    resetState();
}

function switchChat(id) {
    if (isStreamActive) { showToast('请等待回答完成'); return; }
    currentChatId = id;
    safeStorageSet(LAST_CHAT_KEY, id);
    resetState();
    updateChatList();
    displayMessages();
}

function deleteChat(id, e) {
    e.stopPropagation();
    showConfirmDialog('确定删除该会话？', '删除会话', function() {
        delete chats[id];
        saveChats();
        if (id === currentChatId) newChat();
        else updateChatList();
    });
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

function saveChats() { safeStorageSet(CHATS_KEY, JSON.stringify(chats)); }

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

    // 准备请求 - 直接从DOM读取深度思考开关的实际状态，而非依赖全局变量
    var thinkToggle = document.getElementById('thinkToggle');
    var actualDeepThinking = false;
    if (thinkToggle && thinkToggle.style.display !== 'none') {
        // 方式1：从Layui渲染的switch DOM读取状态
        var layuiSwitch = thinkToggle.querySelector('.layui-form-switch');
        if (layuiSwitch && layuiSwitch.classList.contains('layui-form-onswitch')) {
            actualDeepThinking = true;
        }
        // 方式2：从原生checkbox读取（Layui切换时会同步更新checked属性）
        var deepThinkCheck = document.getElementById('deepThinkCheck');
        if (deepThinkCheck) {
            actualDeepThinking = deepThinkCheck.checked;
        }
    }
    console.log('发送消息，深度思考:', actualDeepThinking, '全局变量:', isDeepThinking);
    var requestBody = {
        modelConfigId: currentModelId,
        messages: [{ role: 'system', content: 'You are a helpful assistant.' }].concat(
            chats[currentChatId].filter(function(m) {
                // 过滤掉content为空的assistant消息，避免API报400错误
                return m.role === 'user' || (m.role === 'assistant' && m.content && m.content.trim());
            }).map(function(m) {
                // 将用户消息中的images字段传递给后端
                if (m.role === 'user' && m.images && m.images.length > 0) {
                    return { role: m.role, content: m.content, images: m.images };
                }
                return { role: m.role, content: m.content };
            })
        ),
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
            showToast('登录已过期，请重新登录');
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
        chats[currentChatId].push(msg);
        // 计算本次增量输入token（仅本次对话新增的输入token，而非累计）
        if (streamUsage && streamUsage.prompt_tokens) {
            var turnInputTokens = streamUsage.prompt_tokens;
            // 查找上一个assistant消息的promptTokens（累计值），用于计算增量
            for (var j = chats[currentChatId].length - 2; j >= 0; j--) {
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
            // 重建footer信息（包含时间、模型、token消耗、复制按钮）
            var existingFooter = streamEl.querySelector('.msg-footer');
            if (existingFooter) {
                existingFooter.remove();
            }
            var footer = createAssistantFooter(msg);
            streamEl.appendChild(footer);
            // 处理特殊内容（代码高亮等）
            processSpecialContent(streamEl);
        }
    } else {
        // 没有任何内容，移除可能残留的streaming元素
        var streamEl = document.getElementById('streaming-msg');
        if (streamEl) streamEl.remove();
    }
    resetState();
    // 不再全量重建DOM，仅在必要时刷新（如切换会话后回来）
    // displayMessages() 会导致闪烁，改为仅更新滚动位置
    var container = document.getElementById('chatContainer');
    if (container) {
        var isNearBottom = container.scrollHeight - container.scrollTop - container.clientHeight < 80;
        if (isNearBottom) {
            container.scrollTop = container.scrollHeight;
        }
        updateScrollNav();
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
    container.scrollTop = container.scrollHeight;
    updateScrollNav();
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
        container.appendChild(streamEl);
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
    processSpecialContent(streamEl);
    // 智能自动滚动：仅在用户处于底部附近时自动跟随，用户向上滚动时不干扰
    var isNearBottom = container.scrollHeight - container.scrollTop - container.clientHeight < 80;
    if (isNearBottom) {
        container.scrollTop = container.scrollHeight;
    }
}

// 创建assistant消息的footer（包含时间、模型、token消耗、复制按钮）
function createAssistantFooter(msg) {
    var footer = document.createElement('div');
    footer.className = 'msg-footer';
    var copyIconSvg = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>';
    if (msg.time) {
        var time = document.createElement('span');
        time.className = 'msg-time-inline';
        time.textContent = msg.time;
        footer.appendChild(time);
    }
    if (msg.modelName) {
        var modelSpan = document.createElement('span');
        modelSpan.className = 'msg-model-name';
        modelSpan.textContent = msg.modelName;
        footer.appendChild(modelSpan);
    }
    // token消耗显示
    if (msg.reasoningTokens !== undefined && msg.reasoningTokens !== null) {
        var thinkToken = document.createElement('span');
        thinkToken.className = 'msg-token-info msg-token-thinking';
        thinkToken.textContent = '思考Token≈' + fmtToken(msg.reasoningTokens);
        footer.appendChild(thinkToken);
    }
    if (msg.completionTokens) {
        var outputToken = document.createElement('span');
        outputToken.className = 'msg-token-info';
        outputToken.textContent = '输出Token≈' + fmtToken(msg.completionTokens);
        footer.appendChild(outputToken);
    }
    // 显示本次输出全部token（增量输入+输出）
    if (msg.turnInputTokens && msg.completionTokens) {
        var totalToken = document.createElement('span');
        totalToken.className = 'msg-token-info msg-token-total';
        totalToken.textContent = '总Token(输入+输出)≈' + fmtToken(msg.turnInputTokens + msg.completionTokens);
        footer.appendChild(totalToken);
    }
    var copyBtn = document.createElement('button');
    copyBtn.className = 'footer-copy-btn';
    copyBtn.innerHTML = copyIconSvg;
    copyBtn.title = '复制';
    copyBtn.onclick = function() { copyMsgContent(this); };
    footer.appendChild(copyBtn);
    return footer;
}

// 创建流式输出期间的footer（包含时间、模型、token计算中动效、复制按钮）
function createStreamingFooter(msg) {
    var footer = document.createElement('div');
    footer.className = 'msg-footer';
    var copyIconSvg = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>';
    if (msg.time) {
        var time = document.createElement('span');
        time.className = 'msg-time-inline';
        time.textContent = msg.time;
        footer.appendChild(time);
    }
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

function createMessageEl(msg) {
    var wrapper = document.createElement('div');
    wrapper.className = 'msg-wrapper ' + msg.role;

    var row = document.createElement('div');
    row.className = 'msg-row';

    // 头像
    var avatar = document.createElement('div');
    avatar.className = 'msg-avatar ' + (msg.role === 'user' ? 'user-av' : 'ai-av');
    avatar.innerHTML = msg.role === 'user'
        ? '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2"><circle cx="12" cy="8" r="5"/><path d="M3 21v-2a7 7 0 0 1 7-7h4a7 7 0 0 1 7 7v2"/></svg>'
        : '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><path d="M12 8h.01"/></svg>';

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
        // 用户端显示输入token
        if (msg.promptTokens) {
            var inputToken = document.createElement('span');
            inputToken.className = 'msg-token-info';
            inputToken.textContent = '输入Token≈' + fmtToken(msg.promptTokens);
            footer.appendChild(inputToken);
        }
        if (msg.time) {
            var time = document.createElement('span');
            time.className = 'msg-time-inline';
            time.textContent = msg.time;
            footer.appendChild(time);
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
    // 处理mermaid代码块 - 先替换为占位符
    var mermaidBlocks = [];
    text = text.replace(/```mermaid\n([\s\S]*?)```/g, function(match, code) {
        var idx = mermaidBlocks.length;
        mermaidBlocks.push(code.trim());
        return '%%MERMAID_' + idx + '%%';
    });

    var html = (typeof marked !== 'undefined' && marked.parse) ? marked.parse(text) : escapeHtml(text).replace(/\n/g, '<br>');

    // 还原mermaid - 添加代码/视图切换工具栏 + 功能按钮
    mermaidBlocks.forEach(function(code, idx) {
        var escapedCode = escapeHtml(code);
        html = html.replace('%%MERMAID_' + idx + '%%',
            '<div class="mermaid-container" data-mermaid-raw="' + escapedCode.replace(/"/g, '"') + '">' +
            '<div class="mermaid-toolbar">' +
            '<div class="mermaid-tabs">' +
            '<button class="mermaid-tab active" onclick="switchMermaidView(this,\'view\')">视图</button>' +
            '<button class="mermaid-tab" onclick="switchMermaidView(this,\'code\')">代码</button>' +
            '</div>' +
            '<div class="mermaid-toolbar-spacer"></div>' +
            '<div class="mermaid-actions">' +
            '<button class="mermaid-action" onclick="mermaidZoomOut(this)" title="缩小">' +
            '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/><line x1="8" y1="11" x2="14" y2="11"/></svg>' +
            '</button>' +
            '<button class="mermaid-action" onclick="mermaidZoomIn(this)" title="放大">' +
            '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/><line x1="11" y1="8" x2="11" y2="14"/><line x1="8" y1="11" x2="14" y2="11"/></svg>' +
            '</button>' +
            '<span class="mermaid-toolbar-sep"></span>' +
            '<button class="mermaid-action" onclick="mermaidDownload(this)" title="下载">' +
            '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>' +
            '<span>下载</span>' +
            '</button>' +
            '<button class="mermaid-action" onclick="mermaidFullscreen(this)" title="全屏">' +
            '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/><line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/></svg>' +
            '<span>全屏</span>' +
            '</button>' +
            '</div>' +
            '</div>' +
            '<div class="mermaid-view"><pre class="mermaid">' + escapedCode + '</pre></div>' +
            '<div class="mermaid-code" style="display:none"><pre><code class="language-mermaid">' + escapedCode + '</code></pre></div>' +
            '</div>');
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

// ===== Mermaid 视图/代码切换 =====
function switchMermaidView(btn, mode) {
    var container = btn.closest('.mermaid-container');
    var toolbar = container.querySelector('.mermaid-toolbar');
    var viewDiv = container.querySelector('.mermaid-view');
    var codeDiv = container.querySelector('.mermaid-code');
    var actions = container.querySelector('.mermaid-actions');

    toolbar.querySelectorAll('.mermaid-tab').forEach(function(t) { t.classList.remove('active'); });
    btn.classList.add('active');

    if (mode === 'code') {
        viewDiv.style.display = 'none';
        codeDiv.style.display = 'block';
        // 代码模式下隐藏功能按钮
        if (actions) actions.classList.add('hidden');
        // 确保代码块已高亮
        codeDiv.querySelectorAll('pre code:not([data-processed])').forEach(function(block) {
            block.dataset.processed = 'true';
            if (typeof hljs !== 'undefined' && hljs.highlightElement) hljs.highlightElement(block);
            var pre = block.parentElement;
            if (!pre.querySelector('.code-header')) {
                var lang = (block.className.match(/language-(\w+)/) || ['', 'text'])[1];
                var header = document.createElement('div');
                header.className = 'code-header';
                header.innerHTML = '<span>' + lang + '</span><button class="code-copy-btn" onclick="copyCode(this)">复制代码</button>';
                pre.insertBefore(header, pre.firstChild);
            }
        });
    } else {
        viewDiv.style.display = 'block';
        codeDiv.style.display = 'none';
        // 视图模式下显示功能按钮
        if (actions) actions.classList.remove('hidden');
    }
}

// ===== Mermaid 缩放 =====
function mermaidZoomIn(btn) {
    var container = btn.closest('.mermaid-container');
    var viewDiv = container.querySelector('.mermaid-view');
    var svg = viewDiv ? viewDiv.querySelector('svg') : null;
    if (!svg) return;
    var currentScale = parseFloat(svg.getAttribute('data-mermaid-scale') || '1');
    var newScale = Math.min(currentScale + 0.25, 5);
    svg.setAttribute('data-mermaid-scale', newScale);
    svg.style.transform = 'scale(' + newScale + ')';
    svg.style.transformOrigin = 'center center';
}

function mermaidZoomOut(btn) {
    var container = btn.closest('.mermaid-container');
    var viewDiv = container.querySelector('.mermaid-view');
    var svg = viewDiv ? viewDiv.querySelector('svg') : null;
    if (!svg) return;
    var currentScale = parseFloat(svg.getAttribute('data-mermaid-scale') || '1');
    var newScale = Math.max(currentScale - 0.25, 0.25);
    svg.setAttribute('data-mermaid-scale', newScale);
    svg.style.transform = 'scale(' + newScale + ')';
    svg.style.transformOrigin = 'center center';
}

// ===== Mermaid 下载 =====
function mermaidDownload(btn) {
    var container = btn.closest('.mermaid-container');
    var viewDiv = container.querySelector('.mermaid-view');
    var svg = viewDiv ? viewDiv.querySelector('svg') : null;
    if (!svg) { showToast('图表未渲染完成'); return; }
    // 克隆svg，添加xmlns
    var clone = svg.cloneNode(true);
    clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
    // 移除缩放transform，保持原始大小
    clone.removeAttribute('data-mermaid-scale');
    clone.style.transform = '';
    var svgData = new XMLSerializer().serializeToString(clone);
    var blob = new Blob([svgData], {type: 'image/svg+xml;charset=utf-8'});
    var url = URL.createObjectURL(blob);
    var a = document.createElement('a');
    a.href = url;
    a.download = 'mermaid-diagram-' + Date.now() + '.svg';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
    showToast('已下载SVG');
}

// ===== Mermaid 全屏 =====
function mermaidFullscreen(btn) {
    var container = btn.closest('.mermaid-container');
    var viewDiv = container.querySelector('.mermaid-view');
    var svg = viewDiv ? viewDiv.querySelector('svg') : null;
    if (!svg) { showToast('图表未渲染完成'); return; }

    // 创建全屏遮罩
    var overlay = document.createElement('div');
    overlay.className = 'mermaid-fullscreen-overlay';

    // 关闭按钮
    var closeBtn = document.createElement('button');
    closeBtn.className = 'mermaid-fullscreen-close';
    closeBtn.innerHTML = '✕';
    closeBtn.onclick = function() { overlay.remove(); };

    // 工具栏
    var fsToolbar = document.createElement('div');
    fsToolbar.className = 'mermaid-fullscreen-toolbar';
    fsToolbar.innerHTML =
        '<button class="mermaid-action" onclick="mermaidFsZoomOut(this)" title="缩小">' +
        '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/><line x1="8" y1="11" x2="14" y2="11"/></svg>' +
        '</button>' +
        '<button class="mermaid-action" onclick="mermaidFsZoomIn(this)" title="放大">' +
        '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/><line x1="11" y1="8" x2="11" y2="14"/><line x1="8" y1="11" x2="14" y2="11"/></svg>' +
        '</button>' +
        '<button class="mermaid-action" onclick="mermaidFsZoomReset(this)" title="重置缩放">' +
        '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/></svg>' +
        '<span>重置</span>' +
        '</button>' +
        '<span class="mermaid-toolbar-sep"></span>' +
        '<button class="mermaid-action" onclick="mermaidFsDownload(this)" title="下载">' +
        '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>' +
        '<span>下载</span>' +
        '</button>';

    // 内容区域 - 克隆SVG
    var contentDiv = document.createElement('div');
    contentDiv.className = 'mermaid-fullscreen-content';
    var svgClone = svg.cloneNode(true);
    // 重置缩放
    svgClone.removeAttribute('data-mermaid-scale');
    svgClone.style.transform = '';
    svgClone.setAttribute('data-fs-scale', '1');
    contentDiv.appendChild(svgClone);

    // 初始化位置和缩放数据
    svgClone.setAttribute('data-fs-translate-x', '0');
    svgClone.setAttribute('data-fs-translate-y', '0');
    svgClone.setAttribute('data-fs-scale', '1');

    // 更新 SVG 变换的辅助函数
    function updateSvgTransform(svg) {
        var scale = parseFloat(svg.getAttribute('data-fs-scale') || '1');
        var tx = parseFloat(svg.getAttribute('data-fs-translate-x') || '0');
        var ty = parseFloat(svg.getAttribute('data-fs-translate-y') || '0');
        svg.style.transform = 'translate(' + tx + 'px, ' + ty + 'px) scale(' + scale + ')';
        svg.style.transformOrigin = 'center center';
    }

    // 鼠标滚轮缩放
    contentDiv.addEventListener('wheel', function(e) {
        e.preventDefault();
        var svg = contentDiv.querySelector('svg');
        if (!svg) return;
        var currentScale = parseFloat(svg.getAttribute('data-fs-scale') || '1');
        // deltaY > 0 表示向下滚动（缩小），< 0 表示向上滚动（放大）
        var delta = e.deltaY > 0 ? -0.15 : 0.15;
        var newScale = Math.max(0.25, Math.min(currentScale + delta, 5));
        if (newScale === currentScale) return;
        svg.setAttribute('data-fs-scale', newScale);
        updateSvgTransform(svg);
    }, { passive: false });

    // 鼠标拖动
    var isDragging = false;
    var startX = 0;
    var startY = 0;
    var startTranslateX = 0;
    var startTranslateY = 0;

    contentDiv.addEventListener('mousedown', function(e) {
        isDragging = true;
        startX = e.clientX;
        startY = e.clientY;
        var svg = contentDiv.querySelector('svg');
        if (!svg) return;
        startTranslateX = parseFloat(svg.getAttribute('data-fs-translate-x') || '0');
        startTranslateY = parseFloat(svg.getAttribute('data-fs-translate-y') || '0');
        contentDiv.style.cursor = 'grabbing';
    });

    document.addEventListener('mousemove', function(e) {
        if (!isDragging) return;
        var svg = contentDiv.querySelector('svg');
        if (!svg) return;
        var dx = e.clientX - startX;
        var dy = e.clientY - startY;
        svg.setAttribute('data-fs-translate-x', (startTranslateX + dx).toString());
        svg.setAttribute('data-fs-translate-y', (startTranslateY + dy).toString());
        updateSvgTransform(svg);
    });

    document.addEventListener('mouseup', function() {
        isDragging = false;
        if (contentDiv) contentDiv.style.cursor = 'grab';
    });

    // 默认光标样式
    contentDiv.style.cursor = 'grab';

    overlay.appendChild(closeBtn);
    overlay.appendChild(fsToolbar);
    overlay.appendChild(contentDiv);

    // ESC关闭
    var escHandler = function(e) {
        if (e.key === 'Escape') { overlay.remove(); document.removeEventListener('keydown', escHandler); }
    };
    document.addEventListener('keydown', escHandler);

    // 点击背景关闭
    overlay.addEventListener('click', function(e) {
        if (e.target === overlay) { overlay.remove(); document.removeEventListener('keydown', escHandler); }
    });

    document.body.appendChild(overlay);
}

// 更新 SVG 变换的辅助函数（全局，供工具栏按钮使用）
function updateFsSvgTransform(svg) {
    var scale = parseFloat(svg.getAttribute('data-fs-scale') || '1');
    var tx = parseFloat(svg.getAttribute('data-fs-translate-x') || '0');
    var ty = parseFloat(svg.getAttribute('data-fs-translate-y') || '0');
    svg.style.transform = 'translate(' + tx + 'px, ' + ty + 'px) scale(' + scale + ')';
    svg.style.transformOrigin = 'center center';
}

// 全屏模式下的缩放
function mermaidFsZoomIn(btn) {
    var overlay = btn.closest('.mermaid-fullscreen-overlay');
    var svg = overlay ? overlay.querySelector('.mermaid-fullscreen-content svg') : null;
    if (!svg) return;
    var currentScale = parseFloat(svg.getAttribute('data-fs-scale') || '1');
    var newScale = Math.min(currentScale + 0.25, 5);
    svg.setAttribute('data-fs-scale', newScale);
    updateFsSvgTransform(svg);
}

function mermaidFsZoomOut(btn) {
    var overlay = btn.closest('.mermaid-fullscreen-overlay');
    var svg = overlay ? overlay.querySelector('.mermaid-fullscreen-content svg') : null;
    if (!svg) return;
    var currentScale = parseFloat(svg.getAttribute('data-fs-scale') || '1');
    var newScale = Math.max(currentScale - 0.25, 0.25);
    svg.setAttribute('data-fs-scale', newScale);
    updateFsSvgTransform(svg);
}

function mermaidFsZoomReset(btn) {
    var overlay = btn.closest('.mermaid-fullscreen-overlay');
    var svg = overlay ? overlay.querySelector('.mermaid-fullscreen-content svg') : null;
    if (!svg) return;
    svg.setAttribute('data-fs-scale', '1');
    svg.setAttribute('data-fs-translate-x', '0');
    svg.setAttribute('data-fs-translate-y', '0');
    updateFsSvgTransform(svg);
}

// 全屏模式下的下载
function mermaidFsDownload(btn) {
    var overlay = btn.closest('.mermaid-fullscreen-overlay');
    var svg = overlay ? overlay.querySelector('.mermaid-fullscreen-content svg') : null;
    if (!svg) return;
    var clone = svg.cloneNode(true);
    clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
    clone.removeAttribute('data-fs-scale');
    clone.style.transform = '';
    var svgData = new XMLSerializer().serializeToString(clone);
    var blob = new Blob([svgData], {type: 'image/svg+xml;charset=utf-8'});
    var url = URL.createObjectURL(blob);
    var a = document.createElement('a');
    a.href = url;
    a.download = 'mermaid-diagram-' + Date.now() + '.svg';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
    showToast('已下载SVG');
}

// ===== 特殊内容处理 =====
function processSpecialContent(container) {
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
        if (typeof hljs !== 'undefined' && hljs.highlightElement) {
            hljs.highlightElement(block);
        }
        var pre = block.parentElement;
        if (pre.querySelector('.code-header')) return;
        var lang = (block.className.match(/language-(\w+)/) || ['', 'text'])[1];
        var header = document.createElement('div');
        header.className = 'code-header';
        header.innerHTML = '<span>' + lang + '</span><button class="code-copy-btn" onclick="copyCode(this)">复制代码</button>';
        pre.insertBefore(header, pre.firstChild);
    });

    // Mermaid渲染（仅在mermaid库可用时执行）
    if (typeof mermaid !== 'undefined' && mermaid.render) {
        container.querySelectorAll('.mermaid:not([data-processed])').forEach(function(el) {
            el.dataset.processed = 'true';
            try {
                var id = 'mermaid-' + Date.now() + '-' + Math.random().toString(36).substr(2,5);
                mermaid.render(id, el.textContent).then(function(result) {
                    el.innerHTML = result.svg;
                    el.classList.remove('mermaid');
                }).catch(function() {});
            } catch(e) {}
        });
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
    loading.innerHTML = '<div class="msg-row"><div class="msg-avatar ai-av"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2"><circle cx="12" cy="12" r="10"/></svg></div><div class="loading-dots"><span></span><span></span><span></span></div></div>';
    container.appendChild(loading);
    container.scrollTop = container.scrollHeight;
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

function toggleSidebar() {
    var sidebar = document.getElementById('sidebar');
    var isMobile = window.innerWidth <= 768;
    if (isMobile) {
        sidebar.classList.toggle('open');
        sidebar.classList.remove('collapsed');
        toggleOverlay(sidebar.classList.contains('open'));
    } else {
        sidebar.classList.toggle('collapsed');
    }
}

function toggleOverlay(show) {
    var ov = document.getElementById('overlay');
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
        setTimeout(function() { if(ov.parentNode) ov.remove(); }, 300);
    }
}

function handleResize() {
    var sidebar = document.getElementById('sidebar');
    if (window.innerWidth > 768) {
        sidebar.classList.remove('open');
        toggleOverlay(false);
    }
}

// ===== 滚动导航 =====
function scrollToTop() {
    var container = document.getElementById('chatContainer');
    if (container) container.scrollTo({ top: 0, behavior: 'smooth' });
}

function scrollToBottom() {
    var container = document.getElementById('chatContainer');
    if (container) container.scrollTo({ top: container.scrollHeight, behavior: 'smooth' });
}

function updateScrollNav() {
    var container = document.getElementById('chatContainer');
    var topBtn = document.getElementById('scrollToTopBtn');
    var bottomBtn = document.getElementById('scrollToBottomBtn');
    if (!container || !topBtn || !bottomBtn) return;

    var scrollTop = container.scrollTop;
    var scrollHeight = container.scrollHeight;
    var clientHeight = container.clientHeight;
    var isAtTop = scrollTop <= 5;
    var isAtBottom = scrollHeight - scrollTop - clientHeight <= 5;

    topBtn.style.display = isAtTop ? 'none' : 'flex';
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
