/**
 * 全局版本号配置
 * 修改此处版本号，所有引用页面自动生效
 */
var APP_VERSION = '2.1.0.26.0723';

/**
 * 渲染版本号到页面中带有 class="app-version" 的元素
 */
function renderVersion() {
    var els = document.querySelectorAll('.app-version');
    els.forEach(function(el) {
        el.textContent = 'v' + APP_VERSION;
    });
}

// DOM 加载完成后自动渲染
document.addEventListener('DOMContentLoaded', renderVersion);
