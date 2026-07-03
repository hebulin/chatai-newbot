// ====================================================================
// ScrollFollowManager - 智能滚动跟随管理器
// --------------------------------------------------------------------
// 实现严格的输出行为规范：
//   1. 会话连续性：不主动切换/创建会话
//   2. 自动滚动跟随（默认）：新内容输出时自动滚到底部
//   3. 用户手动中断：向上滚动即暂停跟随
//   4. 自动恢复：滚到底部（或点击"回到底部"按钮）恢复跟随
//   5. 高速输出节流：50ms 合并、最大 20 次/秒、rAF 队列同步渲染
//   6. 异常处理：页面失焦暂停、移动端触摸暂停、超长内容分段滚动
//   7. 异步内容捕获：MutationObserver 监听 mermaid/表格/代码高亮等异步渲染
//   优先级：用户手动操作 > 自动跟随规则 > 性能优化
// ====================================================================
var ScrollFollowManager = (function () {
    // ===== 配置常量 =====
    var THROTTLE_MS = 50;                 // 两次滚动间隔小于该值则合并为一次
    var MAX_SCROLL_PER_SEC = 20;          // 每秒最大滚动次数
    var MIN_INTERVAL = Math.ceil(1000 / MAX_SCROLL_PER_SEC); // 单次滚动最小间隔(ms)
    var NEAR_BOTTOM_THRESHOLD = 80;       // 距底部多少像素内视为"在底部"
    var LONG_CONTENT_THRESHOLD = 10000;   // 超过该字符数视为超长内容，分段滚动

    // ===== 运行时状态 =====
    var container = null;
    var autoFollowEnabled = true;         // 自动跟随是否激活（核心开关）
    var followBeforeHidden = true;        // 页面失焦前的跟随状态，用于恢复
    var pageVisible = true;              // 页面/标签页是否可见
    var touchPaused = false;             // 移动端触摸暂停标记（需手动滚到底部才解除）
    var lastScrollTime = 0;              // 上一次实际滚动的时间戳
    var rafScheduled = false;            // requestAnimationFrame 是否已调度
    var pendingScroll = false;           // 是否有待执行的滚动请求
    var navTopBtn = null;                // 回到顶部按钮
    var navBottomBtn = null;             // 回到底部按钮
    var navWrap = null;                  // 滚动导航容器
    var bound = false;                   // 事件是否已绑定
    var contentObserver = null;           // MutationObserver 实例（监听异步内容渲染）
    var lastObservedHeight = 0;          // 上一次观测到的容器内容高度
    var programmaticScroll = false;      // 标记当前滚动是否由程序触发（避免误判用户中断）

    // ===== 初始化 =====
    function init(el) {
        container = el;
        if (!container) return;
        navTopBtn = document.getElementById('scrollToTopBtn');
        navBottomBtn = document.getElementById('scrollToBottomBtn');
        navWrap = document.getElementById('scrollNav');
        if (!bound) {
            bindEvents();
            attachContentObserver();
            bound = true;
        }
        // 默认激活跟随
        autoFollowEnabled = true;
        touchPaused = false;
        pageVisible = !document.hidden;
        lastObservedHeight = container.scrollHeight;
        updateNavButtons();
    }

    // ===== 事件绑定 =====
    function bindEvents() {
        if (!container) return;
        // 滚动：检测用户手动滚动意图（向上滚→中断，滚到底→恢复）
        container.addEventListener('scroll', onContainerScroll, { passive: true });
        // 页面可见性
        document.addEventListener('visibilitychange', onVisibilityChange);
        window.addEventListener('blur', onWindowBlur);
        window.addEventListener('focus', onWindowFocus);
        // 移动端触摸：触摸即暂停跟随（触摸结束不自动恢复）
        container.addEventListener('touchstart', onTouchStart, { passive: true });
        // 窗口尺寸变化时重新定位导航按钮
        window.addEventListener('resize', updateNavButtons);
    }

    // ===== 内容观察器：监听容器内 DOM 变化（捕获 mermaid/表格/代码高亮等异步渲染） =====
    function attachContentObserver() {
        if (!container || typeof MutationObserver === 'undefined') return;
        if (contentObserver) return; // 已绑定
        contentObserver = new MutationObserver(onContentMutation);
        // 监听子树所有变化：childList（节点增删）、subtree（后代）、attributes（class/style 变化）
        contentObserver.observe(container, {
            childList: true,
            subtree: true,
            attributes: true
        });
        lastObservedHeight = container.scrollHeight;
    }

    // MutationObserver 回调：检测内容高度变化，触发自动滚动
    function onContentMutation() {
        if (!container) return;
        var currentHeight = container.scrollHeight;
        // 仅当内容高度发生变化时才触发（过滤掉不影响布局的属性变化）
        if (currentHeight !== lastObservedHeight) {
            lastObservedHeight = currentHeight;
            // 内容高度变化（如 mermaid 渲染完成插入 SVG、表格包装等），
            // 同步滚动到底部（无 rAF 延迟），避免底部先被撑开再弹回的闪烁
            syncScrollToBottom();
        }
    }

    // ===== 滚动事件处理 =====
    function onContainerScroll() {
        // 程序触发的滚动不检测用户中断意图（避免 doScrollNow 自身触发误判）
        if (programmaticScroll) return;
        // 用户触发的滚动：更新跟随状态与导航按钮
        syncFollowStateFromScroll();
        updateNavButtons();
    }

    // 根据当前滚动位置同步自动跟随状态
    function syncFollowStateFromScroll() {
        // 触摸暂停期间，只有真正到达底部才解除
        if (touchPaused) {
            if (isNearBottom()) {
                touchPaused = false;
                autoFollowEnabled = true;
            }
            return;
        }
        if (isNearBottom()) {
            autoFollowEnabled = true;
        } else {
            // 用户向上滚动离开底部 → 中断跟随
            autoFollowEnabled = false;
        }
    }

    // ===== 核心：请求滚动到底部（供流式输出/异步渲染调用，带节流+rAF） =====
    function requestScrollToBottom() {
        // 页面不可见时暂停所有自动滚动
        if (!pageVisible) return;
        // 自动跟随未激活：不滚动，保留用户当前查看位置
        if (!autoFollowEnabled || touchPaused) return;
        // 通过 rAF 队列调度（内部含频率限制）
        scheduleRaf();
    }

    // 通过 requestAnimationFrame 调度滚动，确保与浏览器渲染同步
    // 内含频率限制：每秒最多 MAX_SCROLL_PER_SEC 次，超出部分延后到下一帧
    function scheduleRaf() {
        pendingScroll = true;
        if (rafScheduled) return;
        rafScheduled = true;
        requestAnimationFrame(processPendingScroll);
    }

    // rAF 回调：处理待执行的滚动（含频率限制）
    function processPendingScroll() {
        rafScheduled = false;
        if (!pendingScroll) return;

        var now = Date.now();
        // 频率限制：距上次滚动不足 MIN_INTERVAL 则延后到下一帧重试
        if (now - lastScrollTime < MIN_INTERVAL) {
            pendingScroll = true;
            rafScheduled = true;
            requestAnimationFrame(processPendingScroll);
            return;
        }

        pendingScroll = false;
        doScrollNow();
    }

    // 实际执行滚动到底部（瞬时定位，避免平滑滚动动画导致来回闪烁）
    function doScrollNow() {
        if (!container) return;
        // 再次校验跟随状态（可能在 rAF 等待期间被用户中断）
        if (!pageVisible || !autoFollowEnabled || touchPaused) return;
        // 标记程序触发，避免 scroll 事件误判用户中断
        programmaticScroll = true;
        // 临时禁用平滑滚动：流式输出时频繁设置 scrollTop，
        // 若启用 smooth 会启动多个未完成的滚动动画，导致内容上下来回跳动闪烁
        var prevBehavior = container.style.scrollBehavior;
        container.style.scrollBehavior = 'auto';
        container.scrollTop = container.scrollHeight;
        lastScrollTime = Date.now();
        container.style.scrollBehavior = prevBehavior;
        // 在下一帧解除标记（确保浏览器已处理完本次滚动触发的 scroll 事件）
        requestAnimationFrame(function () { programmaticScroll = false; });
    }

    // 同步滚动到底部（无 rAF 延迟，用于 DOM 内容更新后立即跟随）
    // 解决问题：内容高度增长后，若滚动有延迟，底部会先被撑开再弹回，产生闪烁。
    // 此方法在内容更新后立即同步滚动，使"内容增长"与"视图跟随"在同一帧完成。
    function syncScrollToBottom() {
        if (!container) return;
        if (!pageVisible || !autoFollowEnabled || touchPaused) return;
        programmaticScroll = true;
        var prevBehavior = container.style.scrollBehavior;
        container.style.scrollBehavior = 'auto';
        container.scrollTop = container.scrollHeight;
        lastScrollTime = Date.now();
        container.style.scrollBehavior = prevBehavior;
        requestAnimationFrame(function () { programmaticScroll = false; });
    }

    // ===== 立即滚动（无节流，用于切换会话/首次加载） =====
    function scrollToBottomImmediate() {
        if (!container) return;
        autoFollowEnabled = true;
        touchPaused = false;
        // 取消任何挂起的 rAF 滚动，避免重复
        pendingScroll = false;
        programmaticScroll = true;
        // 瞬时定位，禁用平滑滚动
        var prevBehavior = container.style.scrollBehavior;
        container.style.scrollBehavior = 'auto';
        container.scrollTop = container.scrollHeight;
        lastScrollTime = Date.now();
        lastObservedHeight = container.scrollHeight;
        container.style.scrollBehavior = prevBehavior;
        requestAnimationFrame(function () { programmaticScroll = false; });
        updateNavButtons();
    }

    // ===== 用户主动操作：点击"回到底部" =====
    function userScrollToBottom() {
        if (!container) return;
        // 用户手动操作优先级最高：强制恢复跟随
        autoFollowEnabled = true;
        touchPaused = false;
        pendingScroll = false;
        programmaticScroll = true;
        container.scrollTo({ top: container.scrollHeight, behavior: 'smooth' });
        lastScrollTime = Date.now();
        lastObservedHeight = container.scrollHeight;
        // 平滑滚动持续时间较长，延迟解除标记
        setTimeout(function () { programmaticScroll = false; }, 500);
        updateNavButtons();
    }

    // ===== 用户主动操作：点击"回到顶部" =====
    function userScrollToTop() {
        if (!container) return;
        // 用户主动查看历史 → 中断跟随
        autoFollowEnabled = false;
        programmaticScroll = true;
        container.scrollTo({ top: 0, behavior: 'smooth' });
        setTimeout(function () { programmaticScroll = false; }, 500);
        updateNavButtons();
    }

    // ===== 页面可见性处理 =====
    function onVisibilityChange() {
        if (document.hidden) {
            pageVisible = false;
            followBeforeHidden = autoFollowEnabled;
        } else {
            pageVisible = true;
            // 重新获得焦点：若此前未手动中断（且未触摸暂停），恢复跟随并补滚一次
            if (followBeforeHidden && !touchPaused) {
                autoFollowEnabled = true;
                doScrollNow();
            }
        }
    }

    function onWindowBlur() {
        pageVisible = false;
        followBeforeHidden = autoFollowEnabled;
    }

    function onWindowFocus() {
        pageVisible = true;
        if (followBeforeHidden && !touchPaused) {
            autoFollowEnabled = true;
            doScrollNow();
        }
    }

    // ===== 移动端触摸处理 =====
    function onTouchStart() {
        // 触摸屏幕立即暂停自动滚动，触摸结束不恢复，需用户手动滚到底部
        touchPaused = true;
        autoFollowEnabled = false;
    }

    // ===== 导航按钮（回到顶部/底部）显隐与定位 =====
    function updateNavButtons() {
        if (!container) return;
        // 动态定位 scroll-nav 到 chat-container 右下角（fixed 定位）
        if (navWrap) {
            var rect = container.getBoundingClientRect();
            navWrap.style.position = 'fixed';
            navWrap.style.right = (window.innerWidth - rect.right + 16) + 'px';
            navWrap.style.bottom = (window.innerHeight - rect.bottom + 12) + 'px';
        }
        if (!navTopBtn || !navBottomBtn) return;

        var scrollTop = container.scrollTop;
        var scrollHeight = container.scrollHeight;
        var clientHeight = container.clientHeight;
        var isAtTop = scrollTop <= 5;
        var isAtBottom = scrollHeight - scrollTop - clientHeight <= 5;

        navTopBtn.style.display = isAtTop ? 'none' : 'flex';
        // 当自动跟随激活（在底部）时隐藏底部按钮；中断时显示
        navBottomBtn.style.display = isAtBottom ? 'none' : 'flex';
    }

    // ===== 工具方法 =====
    function isNearBottom() {
        if (!container) return true;
        return container.scrollHeight - container.scrollTop - container.clientHeight < NEAR_BOTTOM_THRESHOLD;
    }

    function isAutoFollowing() {
        return autoFollowEnabled && !touchPaused;
    }

    // 重置状态（切换会话时调用）
    function reset() {
        pendingScroll = false;
        rafScheduled = false;
        if (container) lastObservedHeight = container.scrollHeight;
    }

    return {
        init: init,
        requestScrollToBottom: requestScrollToBottom,
        syncScrollToBottom: syncScrollToBottom,
        scrollToBottomImmediate: scrollToBottomImmediate,
        userScrollToBottom: userScrollToBottom,
        userScrollToTop: userScrollToTop,
        updateNavButtons: updateNavButtons,
        isAutoFollowing: isAutoFollowing,
        isNearBottom: isNearBottom,
        reset: reset,
        // 配置常量暴露（便于调试/扩展）
        config: {
            THROTTLE_MS: THROTTLE_MS,
            MAX_SCROLL_PER_SEC: MAX_SCROLL_PER_SEC,
            MIN_INTERVAL: MIN_INTERVAL,
            NEAR_BOTTOM_THRESHOLD: NEAR_BOTTOM_THRESHOLD,
            LONG_CONTENT_THRESHOLD: LONG_CONTENT_THRESHOLD
        }
    };
})();
