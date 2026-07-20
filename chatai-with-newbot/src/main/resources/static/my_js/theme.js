/* Theme Switch - Light/Dark Mode
   仅切换 data-theme 属性，UI 状态由 CSS 通过 [data-theme="light"] 选择器自动响应
*/
(function() {
    var THEME_KEY = 'ai-chat-theme';
    var DARK = 'dark';
    var LIGHT = 'light';

    function safeGet(key) { try { return localStorage.getItem(key); } catch(e) { return null; } }
    function safeSet(key, val) { try { localStorage.setItem(key, val); } catch(e) {} }

    function getTheme() {
        var saved = safeGet(THEME_KEY);
        if (saved === LIGHT || saved === DARK) return saved;
        // 默认暗色
        return DARK;
    }

    function applyTheme(theme) {
        var t = (theme === LIGHT) ? LIGHT : DARK;
        document.documentElement.setAttribute('data-theme', t);
        safeSet(THEME_KEY, t);

        // 通知头像图标更新（如有定义）
        if (typeof updateAvatarIcons === 'function') {
            try { updateAvatarIcons(); } catch (e) {}
        }
    }

    function toggleTheme() {
        var current = getTheme();
        var next = current === DARK ? LIGHT : DARK;
        applyTheme(next);
    }

    // 暴露到全局
    window.toggleTheme = toggleTheme;
    window.getTheme = getTheme;
    window.applyTheme = applyTheme;

    // 立即应用，避免首次闪烁
    applyTheme(getTheme());
})();
