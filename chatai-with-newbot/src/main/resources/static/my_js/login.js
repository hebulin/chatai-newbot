// 检查是否已登录
(function() {
    var token = localStorage.getItem('token');
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

function switchTab(tab) {
    var tabs = document.querySelectorAll('.tab-btn');
    tabs[0].classList.toggle('active', tab === 'login');
    tabs[1].classList.toggle('active', tab === 'register');
    document.getElementById('loginForm').style.display = tab === 'login' ? 'block' : 'none';
    document.getElementById('registerForm').style.display = tab === 'register' ? 'block' : 'none';
    hideError();
}

function showError(msg) {
    var el = document.getElementById('errorMsg');
    el.textContent = msg;
    el.style.display = 'flex';
    setTimeout(function() { el.style.display = 'none'; }, 5000);
}

function hideError() {
    document.getElementById('errorMsg').style.display = 'none';
}

function handleLogin(e) {
    e.preventDefault();
    var username = document.getElementById('loginUsername').value.trim();
    var password = document.getElementById('loginPassword').value;
    var btn = document.getElementById('loginBtn');
    btn.disabled = true;
    btn.textContent = '登录中...';

    fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: username, password: password })
    }).then(function(r) { return r.json(); })
    .then(function(data) {
        if (data.success) {
            localStorage.setItem('token', data.token);
            localStorage.setItem('username', data.username);
            localStorage.setItem('role', data.role);
            window.location.href = '/index.html';
        } else {
            showError(data.message || '登录失败');
        }
    }).catch(function(err) {
        showError('网络错误，请稍后重试');
    }).finally(function() {
        btn.disabled = false;
        btn.textContent = '登录';
    });
    return false;
}

function handleRegister(e) {
    e.preventDefault();
    var username = document.getElementById('regUsername').value.trim();
    var password = document.getElementById('regPassword').value;
    var password2 = document.getElementById('regPassword2').value;
    var btn = document.getElementById('regBtn');

    if (password !== password2) {
        showError('两次输入的密码不一致');
        return false;
    }

    btn.disabled = true;
    btn.textContent = '注册中...';

    fetch('/api/auth/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: username, password: password })
    }).then(function(r) { return r.json(); })
    .then(function(data) {
        if (data.success) {
            localStorage.setItem('token', data.token);
            localStorage.setItem('username', data.username);
            localStorage.setItem('role', data.role);
            window.location.href = '/index.html';
        } else {
            showError(data.message || '注册失败');
        }
    }).catch(function(err) {
        showError('网络错误，请稍后重试');
    }).finally(function() {
        btn.disabled = false;
        btn.textContent = '注册';
    });
    return false;
}
