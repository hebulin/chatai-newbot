// ===== 全局状态 =====
var TOKEN = localStorage.getItem('token');
var currentChatId = null;
var chats = {};
var models = [];
var currentModelId = '';
var isDeepThinking = false;
var isStreamActive = false;
var currentController = null;
var buffer = '';
var currentThinkingContent = '';
var currentAnswerContent = '';
var thinkingStartTime = null;

// ===== 初始化 =====
(function init() {
    if (!TOKEN) { window.location.href = '/login.html'; return; }
    // 初始化marked
    marked.setOptions({
        gfm: true, breaks: true, headerIds: false, mangle: false,
        highlight: function(code, lang) {
            try {
                if (lang && hljs.getLanguage(lang)) return hljs.highlight(code, {language: lang}).value;
                return hljs.highlightAuto(code).value;
            } catch(e) { return code; }
        }
    });
    mermaid.initialize({ startOnLoad: false, theme: 'default' });

    // 显示用户信息
    document.getElementById('usernameDisplay').textContent = localStorage.getItem('username') || '用户';
    if (localStorage.getItem('role') === 'admin') {
        document.getElementById('adminBtn').style.display = 'flex';
    }

    // 加载会话
    try { chats = JSON.parse(localStorage.getItem('chats')) || {}; } catch(e) { chats = {}; }
    var lastId = localStorage.getItem('lastChatId');
    if (lastId && chats[lastId]) { currentChatId = lastId; } else { newChat(); }

    loadModels();
    updateChatList();
    displayMessages();

    // 绑定输入框事件
    var textarea = document.getElementById('userInput');
    textarea.addEventListener('input', autoResize);
    textarea.addEventListener('keydown', function(e) {
        if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); }
    });

    // 响应式
    window.addEventListener('resize', handleResize);
    if (window.innerWidth <= 768) {
        document.getElementById('sidebar').classList.add('collapsed');
    }
})();

// ===== 认证相关 =====
function authHeaders() { return { 'Authorization': 'Bearer ' + TOKEN, 'Content-Type': 'application/json' }; }

function logout() {
    fetch('/api/auth/logout', { method: 'POST', headers: authHeaders() }).catch(function(){});
    localStorage.removeItem('token');
    localStorage.removeItem('username');
    localStorage.removeItem('role');
    window.location.href = '/login.html';
}

function goAdmin() { window.location.href = '/admin.html'; }

// ===== 模型加载 =====
function loadModels() {
    fetch('/api/models', { headers: authHeaders() })
    .then(function(r) {
        if (r.status === 401) { logout(); return; }
        return r.json();
    })
    .then(function(data) {
        if (!data || !data.success) return;
        models = data.data;
        var select = document.getElementById('modelSelect');
        select.innerHTML = '';

        // 按厂商分组
        var groups = {};
        var groupOrder = [];
        models.forEach(function(m) {
            var key = m.providerName || m.providerId || 'custom';
            if (!groups[key]) {
                groups[key] = { name: key, icon: m.providerIcon || '', models: [] };
                groupOrder.push(key);
            }
            groups[key].models.push(m);
        });

        // 如果只有一个厂商，直接平铺
        if (groupOrder.length <= 1) {
            models.forEach(function(m) {
                var opt = document.createElement('option');
                opt.value = m.id;
                opt.textContent = m.displayName;
                opt.dataset.supportsThinking = m.supportsThinking;
                select.appendChild(opt);
            });
        } else {
            // 多个厂商，使用 optgroup 分组
            groupOrder.forEach(function(key) {
                var group = groups[key];
                var optgroup = document.createElement('optgroup');
                optgroup.label = group.icon + ' ' + group.name;
                group.models.forEach(function(m) {
                    var opt = document.createElement('option');
                    opt.value = m.id;
                    opt.textContent = m.displayName;
                    opt.dataset.supportsThinking = m.supportsThinking;
                    optgroup.appendChild(opt);
                });
                select.appendChild(optgroup);
            });
        }

        if (models.length > 0) {
            currentModelId = models[0].id;
            select.value = currentModelId;
            onModelChange();
        }
    }).catch(function(e) { console.error('加载模型失败:', e); });
}

function onModelChange() {
    var select = document.getElementById('modelSelect');
    currentModelId = select.value;
    var opt = select.options[select.selectedIndex];
    var supportsThinking = opt && opt.dataset.supportsThinking === 'true';
    var thinkToggle = document.getElementById('thinkToggle');
    var deepThinkCheck = document.getElementById('deepThinkCheck');
    if (supportsThinking) {
        thinkToggle.style.display = 'flex';
        // 模型支持思考时默认勾选
        isDeepThinking = true;
        deepThinkCheck.checked = true;
    } else {
        thinkToggle.style.display = 'none';
        isDeepThinking = false;
        deepThinkCheck.checked = false;
    }
}

function toggleDeepThinking() {
    isDeepThinking = document.getElementById('deepThinkCheck').checked;
}

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
    localStorage.setItem('lastChatId', currentChatId);
    saveChats();
    updateChatList();
    displayMessages();
    resetState();
}

function switchChat(id) {
    if (isStreamActive) { showToast('请等待回答完成'); return; }
    currentChatId = id;
    localStorage.setItem('lastChatId', id);
    resetState();
    updateChatList();
    displayMessages();
}

function deleteChat(id, e) {
    e.stopPropagation();
    if (!confirm('确定删除该会话？')) return;
    delete chats[id];
    saveChats();
    if (id === currentChatId) newChat();
    else updateChatList();
}

function updateChatList() {
    var list = document.getElementById('chatList');
    list.innerHTML = '';
    var ids = Object.keys(chats);
    ids.forEach(function(id) {
        var msgs = chats[id];
        var first = null;
        for (var i = 0; i < msgs.length; i++) { if (msgs[i].role === 'user') { first = msgs[i]; break; } }
        var title = first ? first.content.substring(0, 20) : '新会话';

        var item = document.createElement('div');
        item.className = 'chat-item' + (id === currentChatId ? ' active' : '');
        item.onclick = function() { switchChat(id); };
        item.innerHTML = '<span class="title">' + escapeHtml(title) + '</span>' +
            '<button class="delete-btn" onclick="deleteChat(\'' + id + '\', event)" title="删除">' +
            '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>' +
            '</button>';
        list.appendChild(item);
    });

    // 更新标题
    var msgs = chats[currentChatId] || [];
    var firstUser = null;
    for (var i = 0; i < msgs.length; i++) { if (msgs[i].role === 'user') { firstUser = msgs[i]; break; } }
    document.getElementById('chatTitle').textContent = firstUser ?
        firstUser.content.substring(0, 30) + (firstUser.content.length > 30 ? '...' : '') : '新会话';
}

function saveChats() { localStorage.setItem('chats', JSON.stringify(chats)); }

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
function sendMessage() {
    var input = document.getElementById('userInput');
    var btn = document.getElementById('sendButton');

    // 如果正在流式输出，停止
    if (isStreamActive && currentController) {
        currentController.abort();
        finishStream(true);
        return;
    }

    var text = input.value.trim();
    if (!text || !currentModelId) return;

    var userMsg = { role: 'user', content: text, time: nowStr() };
    if (!chats[currentChatId]) chats[currentChatId] = [];
    chats[currentChatId].push(userMsg);
    saveChats();
    input.value = '';
    input.style.height = 'auto';

    displayMessages();
    updateChatList();
    showLoading();

    // 准备请求
    var requestBody = {
        modelConfigId: currentModelId,
        messages: [{ role: 'system', content: 'You are a helpful assistant.' }].concat(
            chats[currentChatId].filter(function(m) { return m.role === 'user' || m.role === 'assistant'; })
        ),
        stream: true,
        deepThinking: isDeepThinking,
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

    var assistantMsg = { role: 'assistant', content: '', time: null };
    var hasResponse = false;

    fetch('/api/chat', {
        method: 'POST',
        headers: { 'Authorization': 'Bearer ' + TOKEN, 'Content-Type': 'application/json' },
        body: JSON.stringify(requestBody),
        signal: currentController.signal
    }).then(function(resp) {
        if (!resp.ok) throw new Error('服务器错误: ' + resp.status);
        hideLoading();
        var reader = resp.body.getReader();
        var decoder = new TextDecoder();

        function read() {
            return reader.read().then(function(result) {
                if (result.done) { finishStream(false); return; }
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
        assistantMsg.content = '请求失败: ' + err.message;
        assistantMsg.time = nowStr();
        chats[currentChatId].push(assistantMsg);
        saveChats();
        displayMessages();
        resetSendBtn();
    });
}

function finishStream(interrupted) {
    isStreamActive = false;
    if (currentThinkingContent || currentAnswerContent) {
        var msg = {
            role: 'assistant',
            content: currentAnswerContent,
            reasoning_content: currentThinkingContent || undefined,
            time: nowStr(),
            interrupted: interrupted || undefined
        };
        if (thinkingStartTime && !msg.thinkingTime) {
            msg.thinkingTime = Math.round((Date.now() - thinkingStartTime) / 1000);
        }
        chats[currentChatId].push(msg);
        saveChats();
    }
    resetState();
    displayMessages();
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
}

function updateStreamingMessage(msg) {
    var container = document.getElementById('chatContainer');
    // 查找或创建流式消息元素
    var streamEl = document.getElementById('streaming-msg');
    if (!streamEl) {
        streamEl = createMessageEl(msg);
        streamEl.id = 'streaming-msg';
        container.appendChild(streamEl);
    } else {
        // 只更新内容，不重建整个DOM
        var bubble = streamEl.querySelector('.msg-bubble');
        if (bubble) {
            bubble.innerHTML = renderMsgContent(msg) + createCopyBtn();
        }
    }
    processSpecialContent(streamEl);
    container.scrollTop = container.scrollHeight;
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

    if (msg.role === 'user') {
        bubble.innerHTML = escapeHtml(msg.content).replace(/\n/g, '<br>') + createCopyBtn();
    } else {
        bubble.innerHTML = renderMsgContent(msg) + createCopyBtn();
    }

    row.appendChild(avatar);
    row.appendChild(bubble);
    wrapper.appendChild(row);

    // 时间
    if (msg.time) {
        var time = document.createElement('div');
        time.className = 'msg-time';
        time.textContent = msg.time;
        wrapper.appendChild(time);
    }

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

    var html = marked.parse(text);

    // 还原mermaid
    mermaidBlocks.forEach(function(code, idx) {
        html = html.replace('%%MERMAID_' + idx + '%%',
            '<div class="mermaid-container"><pre class="mermaid">' + escapeHtml(code) + '</pre></div>');
    });

    return html;
}

function createCopyBtn() {
    return '<button class="msg-copy-btn" onclick="copyMsgContent(this)">复制</button>';
}

function copyMsgContent(btn) {
    var bubble = btn.closest('.msg-bubble');
    var text = '';
    // 优先取answer-content的纯文本
    var answer = bubble.querySelector('.answer-content');
    if (answer) {
        text = answer.innerText;
    } else {
        text = bubble.innerText.replace('复制', '').trim();
    }
    navigator.clipboard.writeText(text).then(function() {
        btn.textContent = '已复制';
        setTimeout(function() { btn.textContent = '复制'; }, 2000);
    }).catch(function() { showToast('复制失败'); });
}

// ===== 特殊内容处理 =====
function processSpecialContent(container) {
    // 代码块处理
    container.querySelectorAll('pre code').forEach(function(block) {
        if (block.dataset.processed) return;
        block.dataset.processed = 'true';
        hljs.highlightElement(block);
        var pre = block.parentElement;
        if (pre.querySelector('.code-header')) return;
        var lang = (block.className.match(/language-(\w+)/) || ['', 'text'])[1];
        var header = document.createElement('div');
        header.className = 'code-header';
        header.innerHTML = '<span>' + lang + '</span><button class="code-copy-btn" onclick="copyCode(this)">复制代码</button>';
        pre.insertBefore(header, pre.firstChild);
    });

    // Mermaid渲染
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
    var t = document.getElementById('toast');
    t.textContent = msg;
    t.classList.add('show');
    setTimeout(function() { t.classList.remove('show'); }, 2500);
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

function autoResize() {
    var ta = document.getElementById('userInput');
    ta.style.height = 'auto';
    ta.style.height = Math.min(ta.scrollHeight, 150) + 'px';
}

function escapeHtml(str) {
    var div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}

function nowStr() {
    return new Date().toLocaleString('zh-CN');
}
