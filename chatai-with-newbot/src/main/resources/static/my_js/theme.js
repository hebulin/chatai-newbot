/* Theme Switch - Light/Dark Mode */
(function() {
    var THEME_KEY = 'ai-chat-theme';
    var DARK = 'dark';
    var LIGHT = 'light';

    function getTheme() {
        var saved = localStorage.getItem(THEME_KEY);
        if (saved === LIGHT || saved === DARK) return saved;
        return DARK;
    }

    function applyTheme(theme) {
        document.documentElement.setAttribute('data-theme', theme);
        localStorage.setItem(THEME_KEY, theme);
        var btn = document.getElementById('themeToggleBtn');
        if (btn) {
            if (theme === LIGHT) {
                btn.innerHTML = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>';
                btn.title = '切换暗色主题';
            } else {
                btn.innerHTML = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/></svg>';
                btn.title = '切换浅色主题';
            }
        }
    }

    function toggleTheme() {
        var current = getTheme();
        var next = current === DARK ? LIGHT : DARK;
        applyTheme(next);
    }

    window.toggleTheme = toggleTheme;
    window.getTheme = getTheme;
    window.applyTheme = applyTheme;

    applyTheme(getTheme());
})();
