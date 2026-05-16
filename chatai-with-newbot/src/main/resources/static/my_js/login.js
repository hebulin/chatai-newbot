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

// 登录/注册表单切换
function showRegisterForm() {
    document.getElementById('loginFormArea').style.display = 'none';
    document.getElementById('registerFormArea').style.display = 'block';
}
function showLoginForm() {
    document.getElementById('registerFormArea').style.display = 'none';
    document.getElementById('loginFormArea').style.display = 'block';
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
        btn.text('登录中...');

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
                safeStorageSet('token', data.token);
                safeStorageSet('username', data.username);
                safeStorageSet('role', data.role);
                window.location.href = '/index.html';
            } else {
                layer.msg(data.message || '登录失败', { icon: 2, anim: 6 });
            }
        }).catch(function(err) {
            layer.msg('网络错误，请稍后重试', { icon: 2, anim: 6 });
        }).finally(function() {
            btn.removeClass('layui-btn-disabled').prop('disabled', false);
            btn.text('登录');
        });

        return false;
    });

    // 注册表单提交
    form.on('submit(registerSubmit)', function(data) {
        var btn = $('#regBtn');

        if (data.field.password !== data.field.password2) {
            layer.msg('两次输入的密码不一致', { icon: 2, anim: 6 });
            return false;
        }

        btn.addClass('layui-btn-disabled').prop('disabled', true);
        btn.text('注册中...');

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
            } else {
                layer.msg(data.message || '注册失败', { icon: 2, anim: 6 });
            }
        }).catch(function(err) {
            layer.msg('网络错误，请稍后重试', { icon: 2, anim: 6 });
        }).finally(function() {
            btn.removeClass('layui-btn-disabled').prop('disabled', false);
            btn.text('注册');
        });

        return false;
    });
});
