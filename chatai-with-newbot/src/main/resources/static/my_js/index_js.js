let isResponding = false;
let currentController = null;
let isDeepThinking = false;

// 当前会话ID
let currentChatId = null;
// 存储所有会话
let chats = JSON.parse(localStorage.getItem('chats')) || {};

// 当前选择的模型
let currentModel = 'ali-deepseek';

// 添加会话状态管理
const chatStates = {};

// 修改会话状态管理
const activeStreams = new Map();  // 存储活动的流
const messageBuffers = new Map();  // 存储消息缓冲
let isStreamActive = false;  // 标记是否有活动的流

let buffer = ''; // 初始化缓冲区
let currentThinkingContent = ''; // 用于存储思考内容
let currentAnswerContent = ''; // 用于存储回答内容
let thinkingStartTime = null; // 记录思考开始时间

// 添加全局变量
let currentTemperature = 1.3;

// 添加模型温度系数支持映射
const modelTemperatureSupport = {
    'deepseek': true,  // 官方源支持温度系数
    'ali-deepseek': false,
    'qwen-max': false,
    'qwen-plus': false,
    'qwen-turbo': false
};

// 页面加载时初始化
window.onload = function () {
    // 配置 marked
    marked.setOptions({
        gfm: true,
        breaks: true,
        headerIds: false,
        mangle: false,
        highlight: function (code, lang) {
            try {
                if (lang && hljs.getLanguage(lang)) {
                    return hljs.highlight(code, {language: lang}).value;
                }
                return hljs.highlightAuto(code).value;
            } catch (e) {
                console.error('代码高亮失败:', e);
                return code;
            }
        }
    });

    // 创建设置弹窗 - 移到前面
    createSettingsModal();

    // 设置默认温度系数
    const storedTemperature = localStorage.getItem('currentTemperature');
    if (storedTemperature) {
        currentTemperature = parseFloat(storedTemperature);
        document.getElementById('temperatureInput').value = currentTemperature;
    } else {
        currentTemperature = 1.3;
        document.getElementById('temperatureInput').value = currentTemperature;
    }

    // 初始化页面
    initializePage();

    // 初始化响应式布局
    const isMobile = window.innerWidth <= 768;
    const sidebar = document.getElementById('sidebar');

    // 在移动设备上默认折叠侧边栏
    if (isMobile && !sidebar.classList.contains('collapsed')) {
        sidebar.classList.add('collapsed');
    }

    // 添加输入框事件监听
    const textarea = document.getElementById('userInput');
    textarea.addEventListener('input', autoResizeTextarea);

    // 添加输入框失去焦点事件
    textarea.addEventListener('blur', function() {
        if (this.value.trim() === '') {
            this.style.height = 'auto';
        }
    });

    // 初始化代码块处理
    initializeCodeBlocks();

    // 初始化温度设置状态
    updateTemperatureSettingsState();
};

// 将页面初始化逻辑移到单独的函数
function initializePage() {
    // 原来的初始化代码
    const lastChatId = localStorage.getItem('lastChatId');
    if (lastChatId) {
        currentChatId = lastChatId;
        displayMessages(chats[currentChatId]);
    } else {
        newChat();
    }
    updateChatList();
}

// 切换会话
function switchChat(chatId) {
    if (isStreamActive) {
        showNotification('请等待当前回答完成后再切换会话');
        return;
    }

    // 清理思考相关的变量
    currentThinkingContent = '';
    currentAnswerContent = '';
    thinkingStartTime = null;

    currentChatId = chatId;
    localStorage.setItem('lastChatId', chatId);
    displayMessages(chats[currentChatId]); // 确保显示所有内容，包括被中断的回答
    updateChatList();

}

// 创建新会话
function newChat() {
    // 如果有正在进行的对话，显示提示并返回
    if (isStreamActive) {
        showNotification('请等待当前回答完成后再新建会话');
        return;
    }

    // 清理全局变量
    currentThinkingContent = '';
    currentAnswerContent = '';
    thinkingStartTime = null;

    currentChatId = Date.now().toString();
    chats[currentChatId] = [];
    localStorage.setItem('lastChatId', currentChatId);
    updateChatList();

    // 立即更新UI，确保标题显示"新会话"
    document.getElementById('chatContainer').innerHTML = '';
    document.getElementById('chatTitle').textContent = '新会话';

    saveChats();

    // 显示创建成功的提示
    const tipDiv = document.createElement('div');
    tipDiv.className = 'system-message';
    tipDiv.textContent = `新会话`;
    document.getElementById('chatContainer').appendChild(tipDiv);

    setTimeout(() => {
        tipDiv.remove();
    }, 3000);
}

// 更新会话列表
function updateChatList() {
    const chatList = document.getElementById('chatList');
    chatList.innerHTML = '';
    Object.keys(chats).forEach(chatId => {
        const chatItem = document.createElement('div');
        chatItem.className = 'chat-list-item';
        chatItem.id = `chat-${chatId}`;

        // 创建会话按钮
        const button = document.createElement('button');
        button.onclick = () => switchChat(chatId);

        // 创建会话标题容器
        const titleSpan = document.createElement('span');
        const firstMessage = chats[chatId].find(msg => msg.role === 'user');
        const title = firstMessage
            ? firstMessage.content.slice(0, 12) + (firstMessage.content.length > 12 ? '...' : '')
            : `新会话`;
        titleSpan.textContent = title;

        // 创建删除按钮
        const deleteBtn = document.createElement('span');
        deleteBtn.className = 'chat-delete-btn';
        deleteBtn.innerHTML = `
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M3 6h18"></path>
                <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"></path>
                <path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
            </svg>
        `;
        deleteBtn.onclick = (e) => {
            e.stopPropagation();
            deleteSingleChat(chatId);
        };

        if (chatId === currentChatId) {
            button.classList.add('active');
        }

        button.appendChild(titleSpan);
        button.appendChild(deleteBtn);
        chatItem.appendChild(button);
        chatList.appendChild(chatItem);
    });
}

// 添加删除单个会话的函数
function deleteSingleChat(chatId) {
    // 创建确认对话框
    const confirmDialog = document.createElement('div');
    confirmDialog.className = 'confirm-dialog';
    confirmDialog.innerHTML = `
        <div class="confirm-dialog-content">
            <p>确定要删除这个会话吗？</p>
            <div class="confirm-dialog-buttons">
                <button id="confirmDelete" class="styled-button">确认删除</button>
                <button id="cancelDelete" class="styled-button">取消</button>
            </div>
        </div>
    `;
    document.body.appendChild(confirmDialog);

    // 添加确认和取消按钮的事件监听
    document.getElementById('confirmDelete').addEventListener('click', function() {
        delete chats[chatId];
        localStorage.setItem('chats', JSON.stringify(chats));

        // 如果删除的是当前会话，创建新会话
        if (chatId === currentChatId) {
            newChat();
        } else {
            updateChatList();
        }

        confirmDialog.remove();
        showNotification('会话已删除');
    });

    document.getElementById('cancelDelete').addEventListener('click', function() {
        confirmDialog.remove();
    });
}

// 切换深度思考模式
function toggleDeepThinking() {
    const button = document.getElementById('deepThinking');
    isDeepThinking = !isDeepThinking;
    button.classList.toggle('active');
}

// 切换模型
function switchModel() {
    const select = document.getElementById('modelSelect');
    currentModel = select.value;
    updateTemperatureSettingsState();

    const modelNames = {
        'ali-deepseek': 'DeepSeek-V3(ali源-稳定)',
        'deepseek': 'DeepSeek-V3(官方源-慢)',
        'qwen-max': '通义千问-Max(能力最强)',
        'qwen-plus': '通义千问-Plus(能力均衡)',
        'qwen-turbo': '通义千问-Turbo(速度最快)'
    };

    const tipDiv = document.createElement('div');
    tipDiv.className = 'system-message';
    tipDiv.textContent = `已切换至 ${modelNames[currentModel]} 模型`;
    document.getElementById('chatContainer').appendChild(tipDiv);

    setTimeout(() => {
        tipDiv.remove();
    }, 3000);
}

// 添加错误处理函数
function handleError(error, requestData) {
    console.error('Error:', error);
    let errorMessage = {
        role: 'system',
        content: '',
        time: new Date().toLocaleString('zh-CN'),
        isError: true
    };

    if (error.name === 'AbortError') {
        errorMessage.content = '回答已被中断';
    } else if (error.message.includes('Failed to fetch') || error.message.includes('NetworkError')) {
        errorMessage.content = '网络连接错误，请检查网络连接后重试';
    } else if (error.message.includes('timeout')) {
        errorMessage.content = '请求超时，请重试';
    } else {
        errorMessage.content = `发生错误: ${error.message}`;
    }

    // 移除未完成的消息
    const lastMessage = chats[currentChatId][chats[currentChatId].length - 1];
    if (lastMessage && lastMessage.role === 'user') {
        // 保存这条用户消息用于重试
        const lastUserMessage = lastMessage;
        // 添加错误提示
        chats[currentChatId].push(errorMessage);
        displayMessages(chats[currentChatId]);

        // 添加重试按钮
        const retryButton = document.createElement('button');
        // 添加自定义类名
        retryButton.classList.add('styled-button');
        retryButton.textContent = '重试';
        retryButton.onclick = () => {
            // 移除错误消息
            chats[currentChatId].pop();
            // 重新发送请求
            sendMessage(lastUserMessage);
        };

        const container = document.getElementById('chatContainer');
        container.appendChild(retryButton);
    }

    saveChats();
    resetSendButton();
}

// 发送消息函数
function sendMessage(retryMessage = null) {
    const input = document.getElementById('userInput');
    const sendButton = document.getElementById('sendButton');

    // 如果当前会话有活动的流，则停止它
    if (activeStreams.has(currentChatId) && !retryMessage) {
        const controller = activeStreams.get(currentChatId);
        controller.abort();
        activeStreams.delete(currentChatId);
        messageBuffers.delete(currentChatId);

        // 保存被中断的回答内容
        if (currentThinkingContent || currentAnswerContent) {
            const interruptedMessage = {
                role: 'assistant',
                content: currentAnswerContent,
                reasoning_content: currentThinkingContent,
                time: new Date().toLocaleString('zh-CN'),
                interrupted: true // 标记为被中断
            };
            chats[currentChatId].push(interruptedMessage);
            saveChats();
        }

        // 清理所有缓冲区和状态
        currentThinkingContent = '';
        currentAnswerContent = '';
        thinkingStartTime = null;
        buffer = ''; // 清理解码器缓冲区
        isStreamActive = false;

        resetSendButton();
        displayMessages(chats[currentChatId]); // 确保显示被中断的内容
        return;
    }

    // 在开始新的请求前清理所有缓冲区
    buffer = '';
    currentThinkingContent = '';
    currentAnswerContent = '';
    thinkingStartTime = null;

    const message = retryMessage ? retryMessage.content : input.value.trim(); // 获取消息内容
    if (!message) return; // 如果消息为空，直接返回

    const userMessage = retryMessage || {
        role: 'user',
        content: message,
        time: new Date().toLocaleString('zh-CN') // 设置消息时间
    };

    if (!retryMessage) {
        chats[currentChatId].push(userMessage);
        displayMessages(chats[currentChatId]);
        updateChatList();
        input.value = '';
        input.style.height = 'auto';

        // 添加加载动画
        const chatContainer = document.getElementById('chatContainer');
        const loadingDiv = document.createElement('div');
        loadingDiv.className = 'message-content-row';
        loadingDiv.innerHTML = `
            <div class="message-avatar assistant-avatar">
                <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2">
                    <circle cx="12" cy="12" r="10"/>
                    <path d="M12 16v-4"/>
                    <path d="M12 8h.01"/>
                </svg>
            </div>
            <div class="assistant-loading">
                <div class="loading-dot"></div>
                <div class="loading-dot"></div>
                <div class="loading-dot"></div>
            </div>
        `;
        chatContainer.appendChild(loadingDiv);
        chatContainer.scrollTop = chatContainer.scrollHeight;
    } else {
        displayMessages(chats[currentChatId]);
    }

    const requestData = {
        messages: [
            {role: 'system', content: 'You are a helpful assistant.'},
            ...chats[currentChatId]
        ],
        model: currentModel,
        deepThinking: isDeepThinking,
        temperature: currentTemperature // 添加温度参数
    };

    isResponding = true; // 设置响应状态为true
    isStreamActive = true; // 设置流状态为true
    sendButton.textContent = '停止回答'; // 修改发送按钮文本
    sendButton.classList.add('stop'); // 添加停止类

    currentController = new AbortController(); // 创建新的控制器
    activeStreams.set(currentChatId, currentController); // 设置当前会话的控制器
    let assistantMessage = {role: 'assistant', content: ''}; // 初始化助手消息
    messageBuffers.set(currentChatId, assistantMessage); // 设置当前会话的消息缓冲

    let hasResponse = false;  // 添加标志，用于跟踪是否收到任何回答

    // 添加超时处理
    const timeout = setTimeout(() => {
        if (isStreamActive && !hasResponse) {  // 只在没有收到任何回答时触发超时
            currentController.abort(); // 中止流
            activeStreams.delete(currentChatId); // 删除当前会话的控制器
            messageBuffers.delete(currentChatId); // 删除当前会话的消息缓冲
            isStreamActive = false; // 设置流状态为false

            // 创建超时消息
            const timeoutMessage = {
                role: 'assistant',
                content: '回答超时，请重试或更换模型后重试。', // 设置超时消息内容
                time: new Date().toLocaleString('zh-CN'), // 设置超时消息时间
                isTimeout: true // 设置超时标志
            };

            // 添加到会话历史
            chats[currentChatId].push(timeoutMessage); // 将超时消息添加到当前会话
            saveChats(); // 保存会话
            displayMessages(chats[currentChatId]); // 显示消息
            resetSendButton(); // 重置发送按钮状态
        }
    }, 40000);  // 40秒超时

    fetch('/api/chat', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify(requestData),
        signal: currentController.signal
    })
        .then(response => {
            if (!response.ok) {
                throw new Error(`服务器响应错误: ${response.status} ${response.statusText}`);
            }
            // 收到响应后移除加载动画
            const loadingDiv = document.querySelector('.message-content-row:last-child');
            if (loadingDiv && loadingDiv.querySelector('.assistant-loading')) {
                loadingDiv.remove();
            }
            return response;
        })
        .then(response => {
            const reader = response.body.getReader();
            const decoder = new TextDecoder();
            buffer = ''; // 重置解码器缓冲区

            function readStream() {
                return reader.read().then(({done, value}) => {
                    if (done) {
                        activeStreams.delete(currentChatId);
                        messageBuffers.delete(currentChatId);
                        isStreamActive = false;
                        clearTimeout(timeout);
                        buffer = ''; // 清理解码器缓冲区

                        // 确保最后的消息被保存
                        if (currentThinkingContent || currentAnswerContent) {
                            assistantMessage.reasoning_content = currentThinkingContent;
                            assistantMessage.content = currentAnswerContent;
                            assistantMessage.time = new Date().toLocaleString('zh-CN');
                            if (thinkingStartTime) {
                                assistantMessage.thinkingTime = Math.round((Date.now() - thinkingStartTime) / 1000);
                            }
                            if (!chats[currentChatId].includes(assistantMessage)) {
                                chats[currentChatId].push(assistantMessage);
                            }
                            saveChats();
                            displayMessages(chats[currentChatId]);
                        }
                        resetSendButton();
                        // 重置所有状态
                        currentThinkingContent = '';
                        currentAnswerContent = '';
                        thinkingStartTime = null;
                        buffer = '';
                        return;
                    }

                    try {
                        const chunk = decoder.decode(value, {stream: true});
                        buffer += chunk;

                        const lines = buffer.split('data:');
                        buffer = lines.pop() || '';

                        let contentUpdated = false;

                        lines.forEach(line => {
                            if (line.trim().startsWith('{')) {
                                try {
                                    if (line.includes('[DONE]')) return;

                                    const jsonData = JSON.parse(line);
                                    if (jsonData.choices && jsonData.choices[0].delta) {
                                        const delta = jsonData.choices[0].delta;
                                        hasResponse = true;

                                        // 处理思考内容
                                        if (delta.reasoning_content) {
                                            if (!thinkingStartTime) {
                                                thinkingStartTime = Date.now();
                                            }
                                            currentThinkingContent += delta.reasoning_content;
                                            assistantMessage.reasoning_content = currentThinkingContent;
                                            contentUpdated = true;
                                        }
                                        // 处理回答内容
                                        if (delta.content) {
                                            currentAnswerContent += delta.content;
                                            assistantMessage.content = currentAnswerContent;
                                            contentUpdated = true;
                                        }

                                        if (contentUpdated) {
                                            const chatContainer = document.getElementById('chatContainer');
                                            const lastMessage = chatContainer.lastElementChild;

                                            if (lastMessage && lastMessage.classList.contains('assistant-message-container')) {
                                                const contentDiv = lastMessage.querySelector('.message');
                                                if (contentDiv) {
                                                    // 清空现有内容
                                                    contentDiv.innerHTML = '';

                                                    // 如果有思考内容，添加思考内容区域
                                                    if (currentThinkingContent) {
                                                        const thinkingDiv = document.createElement('div');
                                                        thinkingDiv.className = 'thinking-content';

                                                        // 添加思考状态标签
                                                        const statusDiv = document.createElement('div');
                                                        statusDiv.className = 'thinking-status' + (delta.reasoning_content ? ' active' : '');

                                                        if (delta.reasoning_content) {
                                                            statusDiv.innerHTML = '正在深度思考中... <span class="thinking-toggle">﹀</span>';
                                                        } else {
                                                            const thinkingTime = Math.round((Date.now() - thinkingStartTime) / 1000);
                                                            statusDiv.innerHTML = `已深度思考 用时${thinkingTime}秒 <span class="thinking-toggle">﹀</span>`;
                                                        }

                                                        // 添加点击事件
                                                        statusDiv.addEventListener('click', function() {
                                                            thinkingDiv.classList.toggle('collapsed');
                                                        });

                                                        thinkingDiv.appendChild(statusDiv);

                                                        // 添加思考内容
                                                        const thinkingText = document.createElement('div');
                                                        thinkingText.className = 'thinking-content-text';
                                                        thinkingText.innerHTML = marked.parse(currentThinkingContent);
                                                        thinkingDiv.appendChild(thinkingText);

                                                        contentDiv.appendChild(thinkingDiv);
                                                    }

                                                    // 如果有回答内容，添加回答内容区域
                                                    if (currentAnswerContent) {
                                                        const answerDiv = document.createElement('div');
                                                        answerDiv.className = 'answer-content';
                                                        answerDiv.innerHTML = marked.parse(currentAnswerContent);
                                                        contentDiv.appendChild(answerDiv);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } catch (e) {
                                    console.warn('JSON 解析警告:', e.message, '原始数据:', line);
                                }
                            }
                        });

                        if (contentUpdated) {
                            const tempMessages = [...chats[currentChatId]];
                            if (!tempMessages.includes(assistantMessage)) {
                                tempMessages.push(assistantMessage);
                            }
                            displayMessages(tempMessages);
                        }

                        return readStream();
                    } catch (error) {
                        throw new Error('处理响应数据时发生错误: ' + error.message);
                    }
                });
            }

            return readStream();
        })
        .catch(error => {
            if (error.name === 'AbortError') {
                clearTimeout(timeout);  // 清除超时计时器
                activeStreams.delete(currentChatId); // 删除当前会话的控制器
                messageBuffers.delete(currentChatId); // 删除当前会话的消息缓冲
                isStreamActive = false; // 设置流状态为false

                // 保存已经输出的内容
                if (currentThinkingContent || currentAnswerContent) {
                    assistantMessage.reasoning_content = currentThinkingContent;
                    assistantMessage.content = currentAnswerContent;
                    assistantMessage.time = new Date().toLocaleString('zh-CN'); // 设置助手消息时间
                    assistantMessage.interrupted = true;  // 标记为被中断
                    if (!chats[currentChatId].includes(assistantMessage)) {
                        chats[currentChatId].push(assistantMessage); // 将助手消息添加到当前会话
                    }
                    saveChats(); // 保存会话
                    displayMessages(chats[currentChatId]);  // 确保显示最终状态
                }
                resetSendButton();
                return;
            }
            clearTimeout(timeout);  // 清除超时计时器
            handleError(error, requestData); // 处理错误
        });
}

// 保存会话到localStorage
function saveChats() {
    localStorage.setItem('chats', JSON.stringify(chats));
}

// 修改显示消息函数
function displayMessages(messages) {
    const container = document.getElementById('chatContainer');
    container.innerHTML = '';

    // 更新聊天标题
    updateChatTitle(messages);

    messages.forEach(msg => {
        const messageContainer = document.createElement('div');
        messageContainer.className = `${msg.role}-message-container`;

        // 添加时间戳
        const timeDiv = document.createElement('div');
        timeDiv.className = 'message-time';
        timeDiv.textContent = msg.time || new Date().toLocaleString('zh-CN');
        messageContainer.appendChild(timeDiv);

        // 创建消息内容行
        const contentRow = document.createElement('div');
        contentRow.className = 'message-content-row';

        // 创建消息内容div
        const contentDiv = document.createElement('div');
        contentDiv.className = `message ${msg.role}-message`;

        // 添加头像
        const avatar = document.createElement('div');
        avatar.className = `message-avatar ${msg.role}-avatar`;
        avatar.innerHTML = msg.role === 'user' ?
            '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2"><circle cx="12" cy="8" r="5"/><path d="M3 21v-2a7 7 0 0 1 7-7h4a7 7 0 0 1 7 7v2"/></svg>' :
            '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><path d="M12 8h.01"/></svg>';

        // 根据消息角色确定内容和头像的顺序
        if (msg.role === 'user') {
            contentRow.appendChild(contentDiv);
            contentRow.appendChild(avatar);
        } else {
            contentRow.appendChild(avatar);
            contentRow.appendChild(contentDiv);
        }

        if (msg.isError || msg.isTimeout) {
            contentDiv.className = 'error-message';
            contentDiv.textContent = msg.content;
        } else if (msg.role === 'system') {
            contentDiv.className = 'system-message';
            contentDiv.textContent = msg.content;
        } else if (msg.role === 'assistant') {
            try {
                // 检查是否包含思考内容
                if (msg.reasoning_content) {
                    const thinkingDiv = document.createElement('div');
                    thinkingDiv.className = 'thinking-content';

                    // 添加思考状态标签
                    const statusDiv = document.createElement('div');
                    statusDiv.className = 'thinking-status';
                    statusDiv.innerHTML = msg.interrupted
                        ? `回答被中断，已深度思考 用时${msg.thinkingTime || '未知'}秒 <span class="thinking-toggle">﹀</span>`
                        : `已深度思考 用时${msg.thinkingTime || '未知'}秒 <span class="thinking-toggle">﹀</span>`;

                    // 添加点击事件
                    statusDiv.addEventListener('click', function() {
                        thinkingDiv.classList.toggle('collapsed');
                    });

                    thinkingDiv.appendChild(statusDiv);

                    // 添加思考内容
                    const thinkingText = document.createElement('div');
                    thinkingText.className = 'thinking-content-text';
                    thinkingText.innerHTML = marked.parse(msg.reasoning_content);
                    thinkingDiv.appendChild(thinkingText);

                    contentDiv.appendChild(thinkingDiv);
                }

                // 如果有回答内容，添加回答内容区域
                if (msg.content) {
                    const answerDiv = document.createElement('div');
                    answerDiv.className = 'answer-content';
                    answerDiv.innerHTML = marked.parse(msg.content);
                    contentDiv.appendChild(answerDiv);
                }

                // 处理表格和代码块
                processMessageContent(contentDiv);

            } catch (e) {
                console.error('Markdown 渲染失败:', e);
                contentDiv.textContent = msg.content;
            }
        } else {
            // 用户消息直接显示文本
            contentDiv.textContent = msg.content;
        }

        messageContainer.appendChild(contentRow);
        container.appendChild(messageContainer);
    });

    container.scrollTop = container.scrollHeight;

    // 在渲染完消息后初始化代码块
    initializeCodeBlocks();
}

// 添加处理消息内容的辅助函数
function processMessageContent(contentDiv) {
    // 处理表格
    contentDiv.querySelectorAll('table').forEach(table => {
        if (!table.parentElement.classList.contains('table-container')) {
            const wrapper = document.createElement('div');
            wrapper.className = 'table-container';
            table.parentNode.insertBefore(wrapper, table);
            wrapper.appendChild(table);
        }
    });

    // 处理代码块
    contentDiv.querySelectorAll('pre code').forEach(block => {
        // 获取语言
        const language = block.className.replace(/language-/, '').trim();
        if (language) {
            block.parentElement.setAttribute('data-language', language);
        }
        // 应用高亮
        hljs.highlightBlock(block);
    });
}

// 添加更新聊天标题的函数
function updateChatTitle(messages) {
    const chatTitle = document.getElementById('chatTitle');
    const firstUserMessage = messages.find(msg => msg.role === 'user');
    chatTitle.textContent = firstUserMessage ?
        (firstUserMessage.content.slice(0, 20) + (firstUserMessage.content.length > 20 ? '...' : '')) :
        '新会话';
}

// 修改侧边栏切换功能
function toggleSidebar() {
    const sidebar = document.getElementById('sidebar');
    const mainContent = document.querySelector('.main-content');
    const isMobile = window.innerWidth <= 768;

    sidebar.classList.toggle('collapsed');
    if (isMobile) {
        sidebar.classList.toggle('expanded');
        handleOverlay(!sidebar.classList.contains('collapsed'));
    }

    // 调整主内容区域宽度
    if (!isMobile) {
        mainContent.style.width = sidebar.classList.contains('collapsed') ? '100%' : `calc(100% - 260px)`;
    }
}

// 改进遮罩层处理
function handleOverlay(show) {
    let overlay = document.getElementById('sidebar-overlay');

    if (show) {
        if (!overlay) {
            overlay = document.createElement('div');
            overlay.id = 'sidebar-overlay';
            document.body.appendChild(overlay);

            overlay.addEventListener('click', () => {
                toggleSidebar();
            });
        }
        // 强制重绘后添加活动类
        requestAnimationFrame(() => {
            overlay.classList.add('active');
        });
    } else if (overlay) {
        overlay.classList.remove('active');
        // 等待动画完成后移除元素
        setTimeout(() => {
            overlay.remove();
        }, 300);
    }
}

// 添加窗口大小变化监听
window.addEventListener('resize', function() {
    const sidebar = document.getElementById('sidebar');
    const mainContent = document.querySelector('.main-content');
    const isMobile = window.innerWidth <= 768;

    if (isMobile) {
        mainContent.style.width = '100%';
        if (!sidebar.classList.contains('collapsed')) {
            handleOverlay(true);
        }
    } else {
        mainContent.style.width = sidebar.classList.contains('collapsed') ? '100%' : `calc(100% - 260px)`;
        handleOverlay(false);
    }
});

// 页面加载时初始化侧边栏状态
window.addEventListener('load', function() {
    const isMobile = window.innerWidth <= 768;
    const sidebar = document.getElementById('sidebar');

    if (isMobile) {
        sidebar.classList.add('collapsed');
    }
});

// 修改重置按钮状态函数
function resetSendButton() {
    const sendButton = document.getElementById('sendButton');
    sendButton.textContent = '发送';
    sendButton.classList.remove('stop');
    isResponding = false;
    isStreamActive = false;
    const input = document.getElementById('userInput');
    input.disabled = false;
}

// 添加通知提示函数
function showNotification(message) {
    const existingNotification = document.querySelector('.floating-notification');
    if (existingNotification) {
        existingNotification.remove();
    }

    const notification = document.createElement('div');
    notification.className = 'floating-notification';
    notification.textContent = message;
    document.body.appendChild(notification);
    setTimeout(() => notification.remove(), 3000);
}

// 修改删除所有会话的功能
function deleteAllChats() {
    // 创建确认对话框
    const confirmDialog = document.createElement('div');
    confirmDialog.className = 'confirm-dialog';
    confirmDialog.innerHTML = `
        <div class="confirm-dialog-content">
            <p>确定要删除所有会话吗？此操作不可恢复。</p>
            <div class="confirm-dialog-buttons">
                <button id="confirmDelete" class="styled-button">确认删除</button>
                <button id="cancelDelete" class="styled-button">取消</button>
            </div>
        </div>
    `;
    document.body.appendChild(confirmDialog);

    // 添加确认和取消按钮的事件监听
    document.getElementById('confirmDelete').addEventListener('click', function() {
        // 清理全局变量
        currentThinkingContent = '';
        currentAnswerContent = '';
        thinkingStartTime = null;

        // 执行删除操作
        chats = {};
        localStorage.removeItem('chats');
        localStorage.removeItem('lastChatId');
        newChat();

        // 移除对话框
        confirmDialog.remove();

        // 显示删除成功提示
        showNotification('所有会话已删除');
    });

    document.getElementById('cancelDelete').addEventListener('click', function() {
        // 仅移除对话框
        confirmDialog.remove();
    });
}

// 修改导出会话功能，添加确认提示
function exportChats() {
    // 创建确认对话框
    const confirmDialog = document.createElement('div');
    confirmDialog.className = 'confirm-dialog';
    confirmDialog.innerHTML = `
        <div class="confirm-dialog-content">
            <p>是否将所有会话内容下载到本地？</p>
            <div class="confirm-dialog-buttons">
                <button id="confirmExport" class="styled-button">确认下载</button>
                <button id="cancelExport" class="styled-button">取消</button>
            </div>
        </div>
    `;
    document.body.appendChild(confirmDialog);

    // 添加确认和取消按钮的事件监听
    document.getElementById('confirmExport').addEventListener('click', function() {
        // 执行导出操作
        let exportText = '';
        Object.entries(chats).forEach(([chatId, messages], index) => {
            if (index > 0) {
                exportText += '\n==============================会话分隔符====================================\n\n';
            }
            messages.forEach(msg => {
                exportText += `[${msg.time || '未知时间'}] ${msg.role}: ${msg.content}\n`;
            });
        });

        const blob = new Blob([exportText], {type: 'text/plain;charset=utf-8'});
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = `chat-export-${new Date().toISOString().slice(0, 10)}.txt`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);

        // 移除对话框
        confirmDialog.remove();

        // 显示导出成功提示
        showNotification('会话内容已下载到本地');
    });

    document.getElementById('cancelExport').addEventListener('click', function() {
        // 仅移除对话框
        confirmDialog.remove();
    });
}

// 修改按键处理
function handleKeyPress(event) {
    // 如果按下Enter键且没有按下Shift键，则发送消息
    if (event.key === 'Enter' && !event.shiftKey && !isResponding) {
        event.preventDefault(); // 阻止默认的换行行为
        sendMessage();
    }
}

// 自动调整输入框高度
function autoResizeTextarea() {
    const textarea = document.getElementById('userInput');

    // 重置高度以获取正确的scrollHeight
    textarea.style.height = 'auto';

    // 设置新高度，但不超过最大高度
    const newHeight = Math.min(textarea.scrollHeight, 120);
    textarea.style.height = newHeight + 'px';
}

// 添加代码块处理函数
function initializeCodeBlocks() {
    // 为所有代码块添加行号和复制按钮
    document.querySelectorAll('pre code').forEach(block => {
        addCopyButton(block.parentElement);
    });
}

// 修改添加复制按钮函数
function addCopyButton(preBlock) {
    // 创建头部容器
    const header = document.createElement('div');
    header.className = 'code-header';

    // 添加语言标签
    const languageSpan = document.createElement('span');
    languageSpan.className = 'code-language';

    // 获取并处理语言标识
    let language = preBlock.querySelector('code').className;

    // 清理语言标识中的额外后缀
    language = language
        .replace('language-', '')
        .replace(' hljs', '')
        .replace('hljs', '')
        .replace('language-xml', 'xml')
        .trim();

    // 语言映射对象
    const languageMap = {
        'js': 'JavaScript',
        'javascript': 'JavaScript',
        'py': 'Python',
        'python': 'Python',
        'html': 'HTML',
        'css': 'CSS',
        'java': 'Java',
        'cpp': 'C++',
        'c': 'C',
        'csharp': 'C#',
        'cs': 'C#',
        'php': 'PHP',
        'ruby': 'Ruby',
        'go': 'Go',
        'rust': 'Rust',
        'swift': 'Swift',
        'kotlin': 'Kotlin',
        'ts': 'TypeScript',
        'typescript': 'TypeScript',
        'shell': 'Shell',
        'bash': 'Bash',
        'sql': 'SQL',
        'json': 'JSON',
        'xml': 'XML',
        'yaml': 'YAML',
        'yml': 'YAML',
        'md': 'Markdown',
        'markdown': 'Markdown'
    };

    // 获取格式化后的语言名称
    let displayLanguage = languageMap[language.toLowerCase()] || language.toUpperCase() || 'Plain Text';

    // 检查是否包含多个语言标识
    const languageParts = displayLanguage.split(' ');
    if (languageParts.length > 1) {
        // 如果包含多个语言标识，只保留第一个
        displayLanguage = languageParts[0];
    }

    languageSpan.textContent = displayLanguage;
    header.appendChild(languageSpan);

    // 创建复制按钮
    const button = document.createElement('button');
    button.className = 'copy-button';
    button.innerHTML = `
        <svg class="copy-icon" viewBox="0 0 24 24" width="18" height="18">
            <path fill="currentColor" d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"/>
        </svg>
    `;

    button.addEventListener('click', async () => {
        const code = preBlock.querySelector('code').textContent;

        try {
            await navigator.clipboard.writeText(code);
            button.innerHTML = `
                <svg class="copy-icon" viewBox="0 0 24 24" width="18" height="18">
                    <path fill="currentColor" d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/>
                </svg>
            `;
            button.classList.add('success');

            setTimeout(() => {
                button.innerHTML = `
                    <svg class="copy-icon" viewBox="0 0 24 24" width="18" height="18">
                        <path fill="currentColor" d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"/>
                    </svg>
                `;
                button.classList.remove('success');
            }, 3000);
        } catch (err) {
            console.error('复制失败:', err);
        }
    });

    header.appendChild(button);
    preBlock.insertBefore(header, preBlock.firstChild);
}

// 改进温度系数提示函数
function toggleTemperatureInfo() {
    const tooltip = document.querySelector('.temperature-tooltip');

    if (!tooltip) {
        // 创建提示内容
        const tooltipContent = `
            <div class="temperature-tooltip">
                <h4>温度系数推荐设置</h4>
                <p>根据不同场景，我们建议您使用以下温度系数：</p>
                <table>
                    <tr><td><strong>场景</strong></td><td><strong>温度系数</strong></td></tr>
                    <tr><td>代码生成/数学解题</td><td><button onclick="applyTemperature(0.0)">0.0</button></td></tr>
                    <tr><td>数据抽取/分析</td><td><button onclick="applyTemperature(1.0)">1.0</button></td></tr>
                    <tr><td>通用对话</td><td><button onclick="applyTemperature(1.3)">1.3</button></td></tr>
                    <tr><td>翻译</td><td><button onclick="applyTemperature(1.3)">1.3</button></td></tr>
                    <tr><td>创意类写作/诗歌创作</td><td><button onclick="applyTemperature(1.5)">1.5</button></td></tr>
                </table>
                <p style="font-size: 12px; margin-top: 8px;">点击数值可直接应用对应系数</p>
            </div>
        `;
        document.querySelector('.temperature-control').insertAdjacentHTML('beforeend', tooltipContent);

        // 添加样式到表格按钮
        const btns = document.querySelectorAll('.temperature-tooltip button');
        btns.forEach(btn => {
            btn.style.background = '#eef1ff';
            btn.style.border = 'none';
            btn.style.color = '#2b7af6';
            btn.style.padding = '2px 8px';
            btn.style.borderRadius = '4px';
            btn.style.cursor = 'pointer';
        });

        // 获取新创建的提示元素
        const newTooltip = document.querySelector('.temperature-tooltip');
        newTooltip.classList.add('show');

        // 添加点击其他区域关闭提示的功能
        setTimeout(() => {
            document.addEventListener('click', closeTooltipOnOutsideClick);
        }, 100);
    } else {
        tooltip.classList.toggle('show');

        if (tooltip.classList.contains('show')) {
            setTimeout(() => {
                document.addEventListener('click', closeTooltipOnOutsideClick);
            }, 100);
        } else {
            document.removeEventListener('click', closeTooltipOnOutsideClick);
        }
    }
}

// 添加点击外部关闭提示的处理函数
function closeTooltipOnOutsideClick(event) {
    const tooltip = document.querySelector('.temperature-tooltip');
    const temperatureControl = document.querySelector('.temperature-control');

    if (tooltip && !temperatureControl.contains(event.target)) {
        tooltip.classList.remove('show');
        document.removeEventListener('click', closeTooltipOnOutsideClick);
    }
}

// 添加一键应用温度系数的函数
function applyTemperature(value) {
    currentTemperature = value;
    document.getElementById('temperatureInput').value = value;
    const tooltip = document.querySelector('.temperature-tooltip');
    if (tooltip) {
        tooltip.classList.remove('show');
    }
    showNotification(`已设置温度系数为 ${value}`);
}

// 更新温度值时保存到本地存储
function updateTemperature(value) {
    const numValue = parseFloat(value);
    if (!isNaN(numValue) && numValue >= 0 && numValue <= 2) {
        currentTemperature = Math.round(numValue * 10) / 10;
        document.getElementById('temperatureInput').value = currentTemperature;
        localStorage.setItem('currentTemperature', currentTemperature);
    }
}

// 调整温度值时保存到本地存储
function adjustTemperature(delta) {
    const newValue = Math.round((currentTemperature + delta) * 10) / 10;
    if (newValue >= 0 && newValue <= 2) {
        currentTemperature = newValue;
        document.getElementById('temperatureInput').value = currentTemperature;
        localStorage.setItem('currentTemperature', currentTemperature);
    }
}

// 添加设置弹窗HTML
function createSettingsModal() {
    const modal = document.createElement('div');
    modal.className = 'settings-modal';
    modal.innerHTML = `
        <div class="settings-content">
            <div class="settings-header">
                <h3>设置</h3>
                <div class="settings-close" onclick="toggleSettings()">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <line x1="18" y1="6" x2="6" y2="18"></line>
                        <line x1="6" y1="6" x2="18" y2="18"></line>
                    </svg>
                </div>
            </div>
            <div class="settings-section">
                <div class="settings-item" id="temperatureSettings">
                    <span>温度系数：</span>
                    <div class="temperature-control">
                        <input type="number" 
                            id="temperatureInput" 
                            value="1.3" 
                            step="0.1" 
                            min="0" 
                            max="2"
                            onchange="updateTemperature(this.value)"
                        >
                        <div class="temperature-buttons">
                            <button onclick="adjustTemperature(0.1)" class="temp-up">▲</button>
                            <button onclick="adjustTemperature(-0.1)" class="temp-down">▼</button>
                        </div>
                    </div>
                    <div class="temperature-info" onclick="toggleTemperatureInfo()">
                        <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <circle cx="12" cy="12" r="10"></circle>
                            <path d="M12 16v-4"></path>
                            <path d="M12 8h.01"></path>
                        </svg>
                    </div>
                </div>
                <div class="settings-tip">注：仅官方源DeepSeek模型支持温度系数调节</div>
                <br/><br/><br/><br/>
                <div class="settings-tip">v1.0.5.25326</div>
            </div>
        </div>
    `;
    document.body.appendChild(modal);
}

// 切换设置弹窗显示状态
function toggleSettings() {
    const modal = document.querySelector('.settings-modal');
    if (!modal) {
        createSettingsModal();
        updateTemperatureSettingsState();
    } else {
        modal.classList.toggle('active');
    }
}

// 更新温度设置状态
function updateTemperatureSettingsState() {
    const temperatureSettings = document.getElementById('temperatureSettings');
    const currentModel = document.getElementById('modelSelect').value;

    if (modelTemperatureSupport[currentModel]) {
        temperatureSettings.classList.remove('disabled');
    } else {
        temperatureSettings.classList.add('disabled');
    }
}