// 安全的localStorage访问封装
function safeStorageGet(key) { try { return localStorage.getItem(key); } catch(e) { return null; } }
function safeStorageSet(key, value) { try { localStorage.setItem(key, value); } catch(e) { console.warn('localStorage不可用:', e.message); } }
function safeStorageRemove(key) { try { localStorage.removeItem(key); } catch(e) {} }

// 检查是否已登录
(function() {
    var token = safeStorageGet('token');
    if (token) {
        fetch('/api/auth/me', {
            headers: { 'Authorization': 'Bearer ' + token }
        }).then(function(r) { return r.json(); })
        .then(function(data) {
            if (data.success) {
                window.location.href = '/index.html';
            }
        }).catch(function() {});
    }
})();

// 记住我 - 读取 / 写入持久化用户名
var REMEMBERED_USER_KEY = 'rememberedUsername';
var REMEMBERED_FLAG_KEY = 'rememberMe';

// 从 localStorage 恢复"记住我"勾选状态与持久化的用户名
function loadRememberedUsername() {
    try {
        var flag = safeStorageGet(REMEMBERED_FLAG_KEY);
        if (flag === '1') {
            var username = safeStorageGet(REMEMBERED_USER_KEY);
            var rail = document.getElementById('rememberMeRail');
            var input = document.getElementById('login-username');
            if (rail) rail.checked = true;
            if (input && username) input.value = username;
        }
    } catch(e) {}
}

// 登录/注册表单切换 - 用 class 切换叠放状态，避免高度突变
function showRegisterForm() {
    var loginF = document.getElementById('loginFormArea');
    var regF = document.getElementById('registerFormArea');
    if (loginF) loginF.classList.remove('form-pane-active');
    if (regF) regF.classList.add('form-pane-active');
    // 切换标题
    var noEl = document.getElementById('stageNo');
    var eyebrowEl = document.getElementById('stageEyebrow');
    var t1El = document.getElementById('stageTitle1');
    var t2El = document.getElementById('stageTitle2');
    if (noEl) noEl.textContent = 'B.';
    if (eyebrowEl) eyebrowEl.textContent = 'SIGN UP · 注册';
    if (t1El) t1El.textContent = '欢迎加入';
    if (t2El) t2El.textContent = 'Join the workshop';
}

// 注册表单切回登录表单，并还原标题文案
function showLoginForm() {
    var loginF = document.getElementById('loginFormArea');
    var regF = document.getElementById('registerFormArea');
    if (regF) regF.classList.remove('form-pane-active');
    if (loginF) loginF.classList.add('form-pane-active');
    var noEl = document.getElementById('stageNo');
    var eyebrowEl = document.getElementById('stageEyebrow');
    var t1El = document.getElementById('stageTitle1');
    var t2El = document.getElementById('stageTitle2');
    if (noEl) noEl.textContent = 'A.';
    if (eyebrowEl) eyebrowEl.textContent = 'SIGN IN · 登录';
    if (t1El) t1El.textContent = '欢迎回来';
    if (t2El) t2El.textContent = 'Welcome back';
}

// ============================================
// 双视图切换（介绍页 ⇄ 登录页，全端统一逻辑）
// body.form-view 为唯一状态源：
//   无该 class = 介绍页可见；有该 class = 登录页可见
// CSS 负责全部过渡动画，JS 只切换 class 与做收尾
// ============================================

// 判断当前是否为手机满屏布局（与 CSS 断点 480px 保持一致）
function isPhoneLayout() {
    return window.innerWidth <= 480;
}

// 切换到登录表单视图的底层操作（不写 history）：
// 加状态 class、重放入场级联动画、过渡结束后滚回顶部并按需聚焦首个输入框
function setFormView() {
    if (document.body.classList.contains('form-view')) return;
    document.body.classList.add('form-view');
    // 重放表单内容的级联浮现：先移除再强制回流后加回，保证每次进入都重新播放
    var stage = document.querySelector('.form-stage');
    if (stage) {
        stage.classList.remove('reveal');
        void stage.offsetWidth;
        stage.classList.add('reveal');
    }
    // 过渡结束（0.5s）后把表单视图滚回顶部，保证登录表单从头完整可见
    var view = document.getElementById('formView');
    if (view) setTimeout(function() { view.scrollTop = 0; }, 520);
    // 过渡结束后聚焦当前表单的首个输入框（仅 PC / 平板；
    // 手机跳过，避免键盘立刻弹出遮住刚滑入的页面）
    setTimeout(function() {
        if (isPhoneLayout()) return;
        var activeForm = document.querySelector('.atelier-form.form-pane-active');
        var firstInput = activeForm ? activeForm.querySelector('input') : null;
        if (!firstInput) return;
        try { firstInput.focus({ preventScroll: true }); } catch (e) { firstInput.focus(); }
    }, 520);
}

// 切换回品牌介绍视图的底层操作（不写 history）：
// 移除状态 class，并把介绍视图滚回顶部
function setBrandView() {
    document.body.classList.remove('form-view');
    var view = document.getElementById('brandView');
    if (view) view.scrollTop = 0;
}

// "立刻体验"入口：介绍页向左淡出、登录页自右滑入，并压入 history 供系统返回键回退
function enterFormView() {
    if (document.body.classList.contains('form-view')) return;
    setFormView();
    // 压入历史条目：安卓返回键 / 浏览器后退先退回介绍页，而不是直接离开页面
    try { history.pushState({ atelierView: 'form' }, ''); } catch (e) {}
}

// 登录页"返回"：先收起键盘，再走 history.back()（与系统返回键共用 popstate 路径）
function backToBrand() {
    var el = document.activeElement;
    if (el && typeof el.blur === 'function') el.blur();
    if (history.state && history.state.atelierView === 'form') {
        try { history.back(); } catch (e) {}
    }
    setBrandView();
    // 键盘收起过程中可视视口逐步还原：延迟复查，杜绝 --vvh 残留把视图压成矮屏
    scheduleViewportResync();
}

// 浏览器前进 / 后退 / 安卓返回键：按历史条目同步视图状态（popstate 不重复写 history）
window.addEventListener('popstate', function() {
    if (history.state && history.state.atelierView === 'form') {
        setFormView();
    } else {
        setBrandView();
    }
});

// ============================================
// 键盘适配：可视视口显著收缩（键盘弹出）时，把高度与顶偏移写入 CSS 变量，
// 两个视图随之收缩到键盘上方的可视区，输入框与提交按钮不再被键盘遮挡。
// 以"见过的最大可视高度"为基线判断键盘开合（不依赖 innerHeight —— iOS 弹出键盘时
// 布局视口也会收缩，差值法会失灵）；键盘关闭事件在部分安卓设备上有延迟或缺失，
// 配合失焦重试与 window.resize 兜底，杜绝收缩态变量残留导致的"矮屏 + 白屏"故障
// ============================================
var vvMaxHeight = 0; // 见过的最大可视视口高度，即键盘关闭时的满屏高度基线

// 同步可视视口尺寸到 CSS 变量：距基线收缩超过 120px 判定为键盘弹出，否则还原为满屏
function syncVisualViewport() {
    if (!window.visualViewport) return;
    var vv = window.visualViewport;
    vvMaxHeight = Math.max(vvMaxHeight, vv.height);
    var root = document.documentElement;
    if (vvMaxHeight - vv.height > 120) {
        root.style.setProperty('--vvh', vv.height + 'px');
        root.style.setProperty('--vv-top', vv.offsetTop + 'px');
    } else {
        root.style.removeProperty('--vvh');
        root.style.removeProperty('--vv-top');
    }
}

// 键盘关闭后事件可能滞后/缺失：延迟复查两次，确保收缩态变量一定被清除
function scheduleViewportResync() {
    setTimeout(syncVisualViewport, 300);
    setTimeout(syncVisualViewport, 900);
}

// 视口事件接线：visualViewport 的 resize/scroll 为主，window.resize 兜底
(function() {
    if (!window.visualViewport) return;
    var lastInnerW = window.innerWidth;
    window.visualViewport.addEventListener('resize', syncVisualViewport);
    window.visualViewport.addEventListener('scroll', syncVisualViewport);
    window.addEventListener('resize', function() {
        // 宽度变化 = 旋转/分屏：重置基线为当前高度，避免旧基线把横屏满屏误判为"键盘弹出"
        if (window.innerWidth !== lastInnerW) {
            lastInnerW = window.innerWidth;
            vvMaxHeight = window.visualViewport.height;
        }
        syncVisualViewport();
    });
    syncVisualViewport();
})();

// 输入框失焦（键盘开始收起）→ 延迟复查视口，防止关闭事件缺失时收缩态残留
document.addEventListener('focusout', function(e) {
    var t = e.target;
    if (!t || !t.classList || !t.classList.contains('field-input')) return;
    scheduleViewportResync();
});

// 输入框聚焦后平滑滚动到可视区中央（键盘弹出、视口收缩之后尤其重要）
document.addEventListener('focusin', function(e) {
    var t = e.target;
    if (!t || !t.classList || !t.classList.contains('field-input')) return;
    setTimeout(function() {
        try { t.scrollIntoView({ block: 'center', behavior: 'smooth' }); }
        catch (err) { t.scrollIntoView(); }
    }, 320);
});

// ============================================
// 卡片等高（仅 PC / 平板 >480px）：让介绍页与登录页卡片尺寸完全一致，
// 切换时如同同一张卡片在更换内容。测量两面板的自然高度、取较大者写入 --card-h，
// 较短的面板由 CSS 用 flex 居中其内容、补齐高度差，故无需写死魔法数值，
// 且随视口自适应。手机为满屏布局，不参与等高
// ============================================
var cardHeightTimer = null;

// 测量两卡片并写入统一高度；窄屏时清除变量、交还满屏布局
function syncCardHeight() {
    var root = document.documentElement;
    // 仅 PC / 平板参与等高，手机满屏布局清除变量
    if (!window.matchMedia('(min-width: 481px)').matches) {
        root.style.removeProperty('--card-h');
        return;
    }
    var b = document.querySelector('.brand-panel');
    var f = document.querySelector('.form-panel');
    if (!b || !f) return;
    // 先清除变量，得到各自不受等高约束的"自然高度"
    // （登录页此时 visibility:hidden，但仍保留布局，offsetHeight 可正常读取）
    root.style.removeProperty('--card-h');
    var h = Math.max(b.offsetHeight, f.offsetHeight);
    if (h > 0) root.style.setProperty('--card-h', h + 'px');
}

// 防抖：旋转 / 拖拽窗口时避免逐帧测量
function scheduleSyncCardHeight() {
    if (cardHeightTimer) clearTimeout(cardHeightTimer);
    cardHeightTimer = setTimeout(syncCardHeight, 150);
}

// 接线：首屏等 Web 字体加载完再量（否则标题高度不准），并兜底 DOM 就绪量一次；
// 窗口尺寸变化、屏幕旋转时防抖重测
(function initCardHeightSync() {
    function first() { syncCardHeight(); }
    if (document.fonts && document.fonts.ready) {
        document.fonts.ready.then(first);
    }
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', first);
    } else {
        first();
    }
    window.addEventListener('resize', scheduleSyncCardHeight);
    window.addEventListener('orientationchange', scheduleSyncCardHeight);
})();

layui.use(['form', 'layer', 'jquery'], function() {
    var form = layui.form;
    var layer = layui.layer;
    var $ = layui.$;

    // 自定义验证规则
    form.verify({
        password: function(value) {
            if (value.length < 4) {
                return '密码至少4个字符';
            }
        },
        confirmPassword: function(value) {
            // 获取注册表单中的密码字段
            var regFormArea = document.getElementById('registerFormArea');
            var passwordInput = regFormArea.querySelector('input[name="password"]');
            if (passwordInput && value !== passwordInput.value) {
                return '两次输入的密码不一致';
            }
        }
    });

    // 登录表单提交
    form.on('submit(loginSubmit)', function(data) {
        var btn = $('#loginBtn');
        btn.addClass('layui-btn-disabled').prop('disabled', true);
        btn.html('<span class="login-btn-loading"></span> 登录中...');

        fetch('/api/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                username: data.field.username,
                password: data.field.password
            })
        }).then(function(r) { return r.json(); })
        .then(function(data) {
            if (data.success) {
                // 记住我：勾选则持久化用户名；未勾选则清除
                var rail = document.getElementById('rememberMeRail');
                if (rail && rail.checked) {
                    safeStorageSet(REMEMBERED_FLAG_KEY, '1');
                    safeStorageSet(REMEMBERED_USER_KEY, data.username || data.field.username);
                } else {
                    safeStorageRemove(REMEMBERED_FLAG_KEY);
                    safeStorageRemove(REMEMBERED_USER_KEY);
                }
                safeStorageSet('token', data.token);
                safeStorageSet('username', data.username);
                safeStorageSet('role', data.role);
                window.location.href = '/index.html';
                // Don't reset button state since we're redirecting
            } else {
                layer.msg(data.message || '登录失败', { icon: 2, anim: 6, shade: 0.3 });
                btn.removeClass('layui-btn-disabled').prop('disabled', false);
                btn.html('<span class="cta-text">进入工坊 · ENTER</span><span class="cta-arrow"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.5"><line x1="5" y1="12" x2="19" y2="12"/><polyline points="12 5 19 12 12 19"/></svg></span>');
            }
        }).catch(function(err) {
            layer.msg('网络错误，请稍后重试', { icon: 2, anim: 6, shade: 0.3 });
            btn.removeClass('layui-btn-disabled').prop('disabled', false);
            btn.html('<span class="cta-text">进入工坊 · ENTER</span><span class="cta-arrow"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.5"><line x1="5" y1="12" x2="19" y2="12"/><polyline points="12 5 19 12 12 19"/></svg></span>');
        });

        return false;
    });

    // 注册表单提交
    form.on('submit(registerSubmit)', function(data) {
        var btn = $('#regBtn');

        if (data.field.password !== data.field.password2) {
            layer.msg('两次输入的密码不一致', { icon: 2, anim: 6, shade: 0.3 });
            return false;
        }

        btn.addClass('layui-btn-disabled').prop('disabled', true);
        btn.html('<span class="login-btn-loading"></span> 注册中...');

        fetch('/api/auth/register', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                username: data.field.username,
                password: data.field.password
            })
        }).then(function(r) { return r.json(); })
        .then(function(data) {
            if (data.success) {
                safeStorageSet('token', data.token);
                safeStorageSet('username', data.username);
                safeStorageSet('role', data.role);
                window.location.href = '/index.html';
                // Don't reset button state since we're redirecting
            } else {
                layer.msg(data.message || '注册失败', { icon: 2, anim: 6 });
                btn.removeClass('layui-btn-disabled').prop('disabled', false);
                btn.text('注册');
            }
        }).catch(function(err) {
            layer.msg('网络错误，请稍后重试', { icon: 2, anim: 6, shade: 0.3 });
            btn.removeClass('layui-btn-disabled').prop('disabled', false);
            btn.text('注册');
        });

        return false;
    });
});
