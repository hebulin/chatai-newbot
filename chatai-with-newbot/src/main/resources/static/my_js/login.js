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
