/* =====================================================================
 * MermaidRenderer - Mermaid 图表渲染模块
 *   - mermaid.initialize({ theme: 'default', securityLevel: 'loose' })
 *   - 直接 container.innerHTML = svg (不做背景剥离等 hack)
 *   - 渲染失败显示错误提示
 * 保留: 卡片 UI、工具栏、全屏(增强)、代码预处理(AI robustness)
 * ===================================================================== */
(function (global) {
  'use strict';

  if (typeof mermaid === 'undefined' || !mermaid.render) {
    console.error('[MermaidRenderer] mermaid 库未加载, 模块将不可用');
    return;
  }

  // ===== 配置 =====
  var MERMAID_THEMES = [
    { key: 'default', name: '默认' },
    { key: 'neutral', name: '中性' },
    { key: 'forest',  name: '森林' },
    { key: 'dark',    name: '深色' },
    { key: 'base',    name: '极简' }
  ];
  var DEFAULT_THEME = 'default';

  // 可由 chat.js 注入
  var TOAST_FN = null;
  var ESCAPE_HTML_FN = null;
  var SAFE_STORAGE_GET = null;
  var SAFE_STORAGE_SET = null;

  var _currentTheme = DEFAULT_THEME;
  var _initialized = false;
  var _lastInitTheme = null;

  // 串行渲染队列 (mermaid v10 并发渲染会相互干扰)
  var _renderQueue = [];
  var _isRendering = false;
  var _uidCounter = 0;

  function nextUid() {
    return 'mermaid-' + Date.now().toString(36) + '-' + (++_uidCounter);
  }

  // ===== 工具 =====
  function getCurrentTheme() {
    if (SAFE_STORAGE_GET) {
      var saved = SAFE_STORAGE_GET('mermaidTheme');
      if (saved && MERMAID_THEMES.some(function (t) { return t.key === saved; })) return saved;
    }
    return _currentTheme;
  }

  function setCurrentTheme(theme) {
    if (!MERMAID_THEMES.some(function (t) { return t.key === theme; })) return;
    _currentTheme = theme;
    if (SAFE_STORAGE_SET) SAFE_STORAGE_SET('mermaidTheme', theme);
  }

  function showToast(msg) {
    if (TOAST_FN) { TOAST_FN(msg); return; }
    console.log('[MermaidRenderer]', msg);
  }

  function escapeHtml(str) {
    if (ESCAPE_HTML_FN) return ESCAPE_HTML_FN(str);
    var div = document.createElement('div');
    div.textContent = str == null ? '' : String(str);
    return div.innerHTML;
  }

  // ===== mermaid.initialize (参考 demo: theme + securityLevel, 不自定义变量) =====
  function ensureInitialized(themeKey) {
    var theme = themeKey || DEFAULT_THEME;
    if (_initialized && theme === _lastInitTheme) return;
    try {
      mermaid.initialize({
        startOnLoad: false,
        theme: theme,
        securityLevel: 'loose'
      });
      _initialized = true;
      _lastInitTheme = theme;
    } catch (e) {
      console.warn('[MermaidRenderer] mermaid.initialize failed:', e);
    }
  }

  // ===== 检测图类型(用于工具栏小标签) =====
  function detectDiagramType(code) {
    if (!code) return 'graph';
    var first = code.trim().split('\n')[0].trim();
    var m = first.match(/^(graph|flowchart|sequenceDiagram|classDiagram|stateDiagram|stateDiagram-v2|gantt|pie|gitGraph|erDiagram|journey|mindmap|timeline|quadrantChart|requirementDiagram|C4Context|C4Container|C4Component|C4Dynamic|sankey|block|packet|architecture)\b/i);
    if (m) return m[1].toLowerCase();
    return 'graph';
  }

  // ===== AI 代码预处理(robustness, 不影响显示, demo 无此逻辑但保留以提升容错) =====
  function normalizeMermaidCode(code) {
    if (!code) return code;
    code = code.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
    var lines = code.split('\n');
    var normalized = [];
    var diagramType = detectDiagramType(code);
    var isFlowchartType = (diagramType === 'flowchart' || diagramType === 'graph' || diagramType === 'unknown');
    var isSequenceType = (diagramType === 'sequencediagram');
    var seqParticipants = [];
    if (isSequenceType) {
      lines.forEach(function (line) {
        var m = line.trim().match(/^participant\s+(\w+)\s+as\s+/);
        if (m) seqParticipants.push(m[1]);
      });
    }
    lines.forEach(function (line) {
      var trimmed = line.trim();
      if (!trimmed || trimmed.startsWith('%')) { normalized.push(line); return; }
      if (isFlowchartType) {
        splitMermaidLine(trimmed).forEach(function (part) {
          var p = part.trim();
          if (!p) return;
          if (p.startsWith('subgraph ')) p = fixSubgraphLine(p);
          p = quoteMermaidLabels(p);
          normalized.push(p);
        });
      } else if (isSequenceType) {
        var p = trimmed;
        if (p.startsWith('subgraph ')) p = fixSubgraphLine(p);
        p = fixSequenceDiagramLine(p, seqParticipants);
        normalized.push(p);
      } else {
        var p2 = trimmed;
        if (p2.startsWith('subgraph ')) p2 = fixSubgraphLine(p2);
        normalized.push(p2);
      }
    });
    return normalized.join('\n');
  }

  function fixSequenceDiagramLine(line, participants) {
    var trimmed = line.trim();
    if (/^(sequenceDiagram|graph|flowchart|classDiagram|stateDiagram|stateDiagram-v2|gantt|pie|gitGraph|erDiagram|journey|mindmap|timeline|quadrantChart)\b/i.test(trimmed)) return line;
    if (/^(participant|actor|create|destroy|note|Note|loop|end|alt|else|opt|rect|autonumber|activate|deactivate)\b/.test(trimmed)) return line;
    if (/^\w+\s*(-?>+|--?>>+|->|\.)+\s*\w*\s*:/.test(trimmed)) return line;
    var target = participants.length > 0 ? participants[0] : 'arr';
    var text = trimmed.replace(/"/g, '\\"');
    return 'Note over ' + target + ': ' + text;
  }

  function fixSubgraphLine(line) {
    return line.replace(/^(subgraph\s+)(.+?)(:::\w+)+(\s*)$/, function (m, prefix, title, style, tail) {
      return prefix + title + tail;
    });
  }

  function splitMermaidLine(line) {
    var parts = [];
    var current = '';
    var bracketDepth = 0;
    var parenDepth = 0;
    var i = 0;
    while (i < line.length) {
      var ch = line[i];
      if (ch === '[') bracketDepth++;
      else if (ch === ']') bracketDepth = Math.max(0, bracketDepth - 1);
      else if (ch === '(') parenDepth++;
      else if (ch === ')') parenDepth = Math.max(0, parenDepth - 1);
      if (bracketDepth <= 0 && parenDepth <= 0) {
        var match = line.substring(i).match(/^(\s{2,}|\t+)/);
        if (match) {
          var sepLen = match[1].length;
          var before = current.trim();
          var after = line.substring(i + sepLen).trim();
          if (before && after) {
            parts.push(current);
            current = '';
            i += sepLen;
            continue;
          }
        }
      }
      current += ch;
      i++;
    }
    if (current.trim()) parts.push(current);
    return parts;
  }

  function quoteMermaidLabels(line) {
    return line.replace(/(\w+)\[([^\]]*)\]/g, function (match, nodeId, label) {
      if (label.startsWith('"') && label.endsWith('"')) return match;
      if (/[(){}\[\]#&]/.test(label)) {
        var escapedLabel = label.replace(/"/g, '\\"');
        return nodeId + '["' + escapedLabel + '"]';
      }
      return match;
    });
  }

  // ===== 清理 mermaid 渲染失败遗留的 body 临时元素 =====
  function cleanupBodyErrors() {
    document.querySelectorAll('body > svg').forEach(function (svg) {
      try {
        var txt = svg.textContent || '';
        if (txt.indexOf('Syntax error') >= 0 || txt.indexOf('Parse error') >= 0 ||
            txt.indexOf('mermaid') >= 0 || txt.indexOf('version') >= 0) {
          svg.remove();
        }
      } catch (e) {}
    });
    document.querySelectorAll('body > div[id^="dmermaid-"], body > div[id^="mermaid-"]').forEach(function (d) {
      if (!d.closest('.chat-container')) d.remove();
    });
    document.querySelectorAll('body > .mermaid, body > [class*="mermaid"]').forEach(function (el) {
      if (!el.closest('.chat-container')) el.remove();
    });
  }

  // ===== 渲染单条 Mermaid (参考 demo: 直接 innerHTML = svg, 串行队列避免并发) =====
  function enqueueRender(el, text) {
    _renderQueue.push({ el: el, text: text });
    drainQueue();
  }

  function drainQueue() {
    if (_isRendering) return;
    var item = _renderQueue.shift();
    if (!item) return;
    var el = item.el;
    var text = item.text;
    if (!el.isConnected) { drainQueue(); return; }
    _isRendering = true;

    var container = el.closest('.mermaid-container');
    var themeToUse = (container && container.dataset.mermaidTheme) ? container.dataset.mermaidTheme : getCurrentTheme();
    ensureInitialized(themeToUse);

    var id = nextUid();
    mermaid.render(id, text).then(function (result) {
      // 参考 demo: 直接插入 SVG, 不做背景剥离
      if (el.isConnected || el.offsetParent) {
        el.innerHTML = result.svg;
        el.classList.remove('mermaid');
      }
    }).catch(function (err) {
      console.warn('[MermaidRenderer] render failed:', err && err.message || err);
      if (el.isConnected) {
        // 流式输出中的 mermaid 块: 渲染失败时保留原始代码文本,
        // 不要显示错误提示, 等待更多代码补全后再尝试渲染
        var streamingContainer = el.closest('.mermaid-container.streaming');
        if (streamingContainer) return;
        el.classList.remove('mermaid');
        el.innerHTML =
          '<div class="mermaid-error-tip"><strong>⚠ Mermaid 图表语法错误</strong>,无法渲染。请检查代码或让 AI 重新生成。</div>' +
          '<pre style="margin:0;"><code class="language-mermaid">' + escapeHtml(text) + '</code></pre>';
      }
    }).then(function () {
      cleanupBodyErrors();
      _isRendering = false;
      if (el.isConnected) {
        var sw = el.closest('.mermaid-scroll-wrapper');
        if (sw) sw.dispatchEvent(new Event('scroll'));
      }
      drainQueue();
    });
  }

  // ===== 暴露给 chat.js: 渲染容器内所有未渲染的 mermaid 块 =====
  function processContainer(container, skipMermaid) {
    if (!container || skipMermaid) return;
    if (typeof mermaid === 'undefined' || !mermaid.render) return;
    cleanupBodyErrors();
    container.querySelectorAll('.mermaid:not([data-processed])').forEach(function (el) {
      var originalText = el.textContent;
      el.dataset.processed = 'true';
      var normalized = normalizeMermaidCode(originalText);
      // 参考 demo: 直接渲染; 先 parse 验证, 失败则用原文重试
      try {
        var parseResult = mermaid.parse(normalized);
        if (parseResult && typeof parseResult.then === 'function') {
          parseResult.then(function () {
            if (el.isConnected) enqueueRender(el, normalized);
          }).catch(function () {
            try {
              var r2 = mermaid.parse(originalText);
              if (r2 && typeof r2.then === 'function') {
                r2.then(function () { if (el.isConnected) enqueueRender(el, originalText); }).catch(function () {});
              }
            } catch (e) {}
          });
        } else {
          if (el.isConnected) enqueueRender(el, normalized);
        }
      } catch (e) {
        try {
          mermaid.parse(originalText);
          if (el.isConnected) enqueueRender(el, originalText);
        } catch (e2) {}
      }
    });
  }

  // ===== 图类型图标 =====
  function getDiagramIcon(typeKey) {
    var s = '<svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">';
    if (typeKey === 'sequencediagram') {
      s += '<line x1="6" y1="3" x2="6" y2="21"/><line x1="18" y1="3" x2="18" y2="21"/><polyline points="6 8 18 8 12 14 6 8 18 8"/>';
    } else if (typeKey === 'gantt') {
      s += '<line x1="3" y1="12" x2="21" y2="12"/><rect x="4" y="9" width="6" height="3"/><rect x="12" y="9" width="8" height="3"/>';
    } else if (typeKey === 'pie') {
      s += '<path d="M12 2v10l8.66 5A10 10 0 1 1 12 2z"/>';
    } else if (typeKey === 'classdiagram') {
      s += '<rect x="3" y="4" width="18" height="16" rx="1"/><line x1="3" y1="10" x2="21" y2="10"/><line x1="3" y1="15" x2="21" y2="15"/>';
    } else if (typeKey.indexOf('state') === 0) {
      s += '<circle cx="6" cy="6" r="2"/><circle cx="6" cy="18" r="2"/><circle cx="18" cy="12" r="2"/><line x1="8" y1="6" x2="16" y2="11"/><line x1="8" y1="18" x2="16" y2="13"/>';
    } else if (typeKey === 'erdiagram') {
      s += '<rect x="3" y="4" width="8" height="6"/><rect x="13" y="4" width="8" height="6"/><rect x="8" y="14" width="8" height="6"/><line x1="11" y1="7" x2="13" y2="7"/><line x1="12" y1="10" x2="12" y2="14"/>';
    } else {
      s += '<rect x="3" y="3" width="7" height="5" rx="1"/><rect x="14" y="3" width="7" height="5" rx="1"/><rect x="8" y="14" width="8" height="5" rx="1"/><line x1="10" y1="8" x2="12" y2="14"/><line x1="14" y1="14" x2="17" y2="8"/>';
    }
    return s + '</svg>';
  }

  // ===== 生成 markdown 中 mermaid 块的卡片 HTML =====
  function renderMermaidBlockTemplate(code) {
    var escaped = escapeHtml(code);
    var typeKey = detectDiagramType(code);
    var typeLabel = typeKey.replace(/diagram$/i, '').toUpperCase();
    return (
      '<div class="mermaid-container" data-mermaid-raw="' + escaped.replace(/"/g, '&quot;') + '">' +
        '<div class="mermaid-toolbar">' +
          '<div class="mermaid-toolbar-left">' +
            '<span class="mermaid-type-tag" title="' + typeKey + '">' + getDiagramIcon(typeKey) + ' ' + typeLabel + '</span>' +
            '<div class="mermaid-tabs">' +
              '<button class="mermaid-tab active" data-act="showView" data-pane="view" title="图表视图">视图</button>' +
              '<button class="mermaid-tab" data-act="showCode" data-pane="code" title="查看源码">代码</button>' +
            '</div>' +
          '</div>' +
          '<div class="mermaid-toolbar-right">' +
            '<button class="mermaid-action icon-only" data-act="copy" title="复制代码">' +
              '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>' +
            '</button>' +
            '<button class="mermaid-action icon-only" data-act="downloadSvg" title="下载 SVG">' +
              '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>' +
            '</button>' +
            '<button class="mermaid-action icon-only" data-act="downloadPng" title="下载 PNG">' +
              '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><path d="M21 15l-5-5L5 21"/></svg>' +
            '</button>' +
            '<button class="mermaid-action icon-only" data-act="fullscreen" title="全屏查看">' +
              '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/><line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/></svg>' +
            '</button>' +
            '<button class="mermaid-action icon-only" data-act="toggleTheme" title="切换主题">' +
              '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="5"/><path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42"/></svg>' +
            '</button>' +
          '</div>' +
        '</div>' +
        '<div class="mermaid-scroll-wrapper">' +
          '<div class="mermaid-view"><pre class="mermaid">' + escaped + '</pre></div>' +
        '</div>' +
        '<div class="mermaid-code" style="display:none"><pre><code class="language-mermaid">' + escaped + '</code></pre></div>' +
      '</div>'
    );
  }

  // ===== 生成流式输出中 mermaid 块的卡片 HTML =====
  // 与 renderMermaidBlockTemplate 类似, 但标记为流式状态:
  //   - 工具栏只显示类型标签 + "流式生成中" 动效提示
  //   - 不显示完整工具栏按钮(复制/下载/全屏/主题等), 等待代码完整后再显示
  //   - 不显示代码视图(代码本身还在持续输出, 切到代码视图体验不佳)
  //   - pre.mermaid 内的内容由 processContainer 在每次流式更新时尝试渲染
  function renderStreamingMermaidBlockTemplate(code) {
    var escaped = escapeHtml(code);
    var typeKey = detectDiagramType(code);
    var typeLabel = typeKey.replace(/diagram$/i, '').toUpperCase();
    return (
      '<div class="mermaid-container streaming" data-mermaid-raw="' + escaped.replace(/"/g, '&quot;') + '" data-streaming="true">' +
        '<div class="mermaid-toolbar">' +
          '<div class="mermaid-toolbar-left">' +
            '<span class="mermaid-type-tag" title="' + typeKey + '">' + getDiagramIcon(typeKey) + ' ' + typeLabel + '</span>' +
            '<span class="mermaid-streaming-tip" title="图表正在边输出边渲染">' +
              '<span class="streaming-dot"></span>' +
              '<span>流式生成中</span>' +
            '</span>' +
          '</div>' +
        '</div>' +
        '<div class="mermaid-scroll-wrapper">' +
          '<div class="mermaid-view"><pre class="mermaid">' + escaped + '</pre></div>' +
        '</div>' +
      '</div>'
    );
  }

  // ===== 事件委托 =====
  function bindToolbarDelegation(root) {
    var target = root || document.body;
    if (target.__mermaidDelegated) return;
    target.__mermaidDelegated = true;
    target.addEventListener('click', function (e) {
      var btn = e.target.closest('.mermaid-action[data-act], .mermaid-tab[data-act]');
      if (!btn) return;
      handleToolbarAction(btn.getAttribute('data-act'), btn);
    });
  }

  function handleToolbarAction(act, btn) {
    var container = btn.closest('.mermaid-container');
    if (!container) return;
    switch (act) {
      case 'copy':         return copyCode(btn);
      case 'zoomIn':       return zoomIn(btn);
      case 'zoomOut':      return zoomOut(btn);
      case 'downloadSvg':  return downloadSvg(btn);
      case 'downloadPng':  return downloadPng(btn);
      case 'fullscreen':   return openFullscreen(btn);
      case 'toggleTheme':  return showThemeMenu(btn);
      case 'showView':     return switchPane(btn, 'view');
      case 'showCode':     return switchPane(btn, 'code');
    }
  }

  function switchPane(btn, pane) {
    var container = btn.closest('.mermaid-container');
    if (!container) return;
    container.querySelectorAll('.mermaid-tab').forEach(function (t) {
      t.classList.toggle('active', t.getAttribute('data-pane') === pane);
    });
    var view = container.querySelector('.mermaid-scroll-wrapper');
    var code = container.querySelector('.mermaid-code');
    if (view) view.style.display = (pane === 'view') ? '' : 'none';
    if (code) code.style.display = (pane === 'code') ? 'block' : 'none';
  }

  // ===== 复制代码 =====
  function copyCode(btn) {
    var container = btn.closest('.mermaid-container');
    if (!container) return;
    var raw = container.getAttribute('data-mermaid-raw') || '';
    if (!raw) {
      var codeEl = container.querySelector('.mermaid-code code');
      if (codeEl) raw = codeEl.textContent;
    }
    var originalHtml = btn.innerHTML;
    var onSuccess = function () {
      btn.innerHTML = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#10b981" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>';
      btn.classList.add('success');
      setTimeout(function () { btn.innerHTML = originalHtml; btn.classList.remove('success'); }, 2000);
      showToast('已复制代码');
    };
    var onFail = function () { showToast('复制失败'); };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(raw).then(onSuccess).catch(function () {
        fallbackCopy(raw) ? onSuccess() : onFail();
      });
    } else {
      fallbackCopy(raw) ? onSuccess() : onFail();
    }
  }

  function fallbackCopy(text) {
    try {
      var ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      var ok = document.execCommand('copy');
      document.body.removeChild(ta);
      return ok;
    } catch (e) { return false; }
  }

  // ===== 缩放 =====
  function getViewSvg(container) {
    if (!container) return null;
    var view = container.querySelector('.mermaid-view');
    return view ? view.querySelector('svg') : null;
  }
  function zoomIn(btn) {
    var svg = getViewSvg(btn.closest('.mermaid-container'));
    if (!svg) return;
    var cur = parseFloat(svg.getAttribute('data-mermaid-scale') || '1');
    var nxt = Math.min(cur + 0.25, 5);
    svg.setAttribute('data-mermaid-scale', nxt);
    svg.style.transform = 'scale(' + nxt + ')';
  }
  function zoomOut(btn) {
    var svg = getViewSvg(btn.closest('.mermaid-container'));
    if (!svg) return;
    var cur = parseFloat(svg.getAttribute('data-mermaid-scale') || '1');
    var nxt = Math.max(cur - 0.25, 0.25);
    svg.setAttribute('data-mermaid-scale', nxt);
    svg.style.transform = 'scale(' + nxt + ')';
  }

  // ===== 下载文件(参考 demo downloadFile) =====
  function downloadFile(content, filename, type) {
    var blob = new Blob([content], { type: type });
    var url = URL.createObjectURL(blob);
    var a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
  }

  // ===== 下载 SVG (参考 demo) =====
  function downloadSvg(btn) {
    var svg = getViewSvg(btn.closest('.mermaid-container'));
    if (!svg) { showToast('图表未渲染完成'); return; }
    var svgString = new XMLSerializer().serializeToString(svg);
    downloadFile(svgString, 'mermaid-' + Date.now() + '.svg', 'image/svg+xml');
    showToast('已下载 SVG');
  }

  // ===== 下载 PNG (参考 demo downloadPNG: SVG -> blob URL -> Image -> canvas 2x) =====
  function downloadPng(btn) {
    var svg = getViewSvg(btn.closest('.mermaid-container'));
    if (!svg) { showToast('图表未渲染完成'); return; }
    svgToPng(svg, 'mermaid-' + Date.now() + '.png');
  }

  function svgToPng(svgElement, filename) {
    var svgData = new XMLSerializer().serializeToString(svgElement);
    var blob = new Blob([svgData], { type: 'image/svg+xml;charset=utf-8' });
    var url = URL.createObjectURL(blob);
    var img = new Image();
    img.onload = function () {
      var canvas = document.createElement('canvas');
      var ctx = canvas.getContext('2d');
      var w = img.width || svgElement.clientWidth || 800;
      var h = img.height || svgElement.clientHeight || 600;
      canvas.width = w * 2;   // 2x 高清
      canvas.height = h * 2;
      ctx.scale(2, 2);
      ctx.drawImage(img, 0, 0, w, h);
      URL.revokeObjectURL(url);
      canvas.toBlob(function (pngBlob) {
        if (!pngBlob) { showToast('PNG 导出失败'); return; }
        var pngUrl = URL.createObjectURL(pngBlob);
        var a = document.createElement('a');
        a.href = pngUrl;
        a.download = filename;
        a.click();
        setTimeout(function () { URL.revokeObjectURL(pngUrl); }, 1000);
        showToast('已下载 PNG');
      }, 'image/png');
    };
    img.onerror = function () { showToast('PNG 导出失败'); URL.revokeObjectURL(url); };
    img.src = url;
  }

  // ===== 主题切换菜单 =====
  function showThemeMenu(btn) {
    var container = btn.closest('.mermaid-container');
    if (!container) return;
    var existing = container.querySelector('.mermaid-theme-menu');
    if (existing) { existing.remove(); return; }
    var cur = container.dataset.mermaidTheme || getCurrentTheme();
    var menu = document.createElement('div');
    menu.className = 'mermaid-theme-menu active';
    var html = '';
    MERMAID_THEMES.forEach(function (t) {
      var checked = t.key === cur;
      html += '<div class="mermaid-theme-menu-item' + (checked ? ' active' : '') + '" data-theme="' + t.key + '">' +
        '<span class="check">' + (checked ? '✓' : '') + '</span>' +
        '<span>' + t.name + '</span>' +
      '</div>';
    });
    menu.innerHTML = html;
    var toolbar = container.querySelector('.mermaid-toolbar');
    if (toolbar) { toolbar.style.position = 'relative'; toolbar.appendChild(menu); }
    else { container.appendChild(menu); }
    menu.querySelectorAll('.mermaid-theme-menu-item').forEach(function (item) {
      item.addEventListener('click', function () {
        applyTheme(container, item.getAttribute('data-theme'));
        menu.remove();
      });
    });
    setTimeout(function () {
      var onDocClick = function (e) {
        if (menu.contains(e.target) || btn.contains(e.target)) return;
        menu.remove();
        document.removeEventListener('click', onDocClick);
      };
      document.addEventListener('click', onDocClick);
    }, 0);
  }

  // ===== 应用主题(重新渲染该图表) =====
  function applyTheme(container, theme) {
    if (!container) return;
    container.dataset.mermaidTheme = theme;
    var view = container.querySelector('.mermaid-view');
    var pre = view ? view.querySelector('pre') : null;
    if (!pre) return;
    var raw = container.getAttribute('data-mermaid-raw');
    if (!raw) return;
    pre.classList.add('mermaid');
    pre.removeAttribute('data-processed');
    pre.innerHTML = escapeHtml(raw);
    ensureInitialized(theme);
    enqueueRender(pre, normalizeMermaidCode(raw));
  }

  // ===== 全屏查看(参考 demo showFullscreenModal: 克隆 SVG 入浅色 modal; 增加缩放/拖动) =====
  function openFullscreen(btn) {
    var container = btn.closest('.mermaid-container');
    if (!container) return;
    var svg = getViewSvg(container);
    if (!svg) { showToast('图表未渲染完成'); return; }

    var overlay = document.createElement('div');
    overlay.className = 'mermaid-fullscreen-overlay';

    var closeBtn = document.createElement('button');
    closeBtn.className = 'mermaid-fullscreen-close';
    closeBtn.innerHTML = '✕';

    var toolbar = document.createElement('div');
    toolbar.className = 'mermaid-fullscreen-toolbar';
    toolbar.innerHTML =
      '<button class="mermaid-action" data-fs-act="zoomOut" title="缩小">' +
        '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/><line x1="8" y1="11" x2="14" y2="11"/></svg>' +
      '</button>' +
      '<button class="mermaid-action" data-fs-act="zoomIn" title="放大">' +
        '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/><line x1="11" y1="8" x2="11" y2="14"/><line x1="8" y1="11" x2="14" y2="11"/></svg>' +
      '</button>' +
      '<button class="mermaid-action" data-fs-act="reset" title="重置缩放">' +
        '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/></svg>' +
        '<span>重置</span>' +
      '</button>' +
      '<span class="mermaid-toolbar-sep"></span>' +
      '<button class="mermaid-action" data-fs-act="downloadSvg" title="下载 SVG">' +
        '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>' +
        '<span>SVG</span>' +
      '</button>' +
      '<button class="mermaid-action" data-fs-act="downloadPng" title="下载 PNG">' +
        '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><path d="M21 15l-5-5L5 21"/></svg>' +
        '<span>PNG</span>' +
      '</button>';

    var content = document.createElement('div');
    content.className = 'mermaid-fullscreen-content';
    var svgClone = svg.cloneNode(true);
    svgClone.removeAttribute('data-mermaid-scale');
    svgClone.style.transform = '';
    svgClone.setAttribute('data-fs-scale', '1');
    svgClone.setAttribute('data-fs-tx', '0');
    svgClone.setAttribute('data-fs-ty', '0');
    content.appendChild(svgClone);

    overlay.appendChild(closeBtn);
    overlay.appendChild(toolbar);
    overlay.appendChild(content);

    var close = function () {
      overlay.remove();
      document.removeEventListener('keydown', escHandler);
    };
    var escHandler = function (e) { if (e.key === 'Escape') close(); };
    closeBtn.onclick = close;
    overlay.addEventListener('click', function (e) { if (e.target === overlay) close(); });
    document.addEventListener('keydown', escHandler);

    function applyFsTransform(s) {
      var sc = parseFloat(s.getAttribute('data-fs-scale') || '1');
      var tx = parseFloat(s.getAttribute('data-fs-tx') || '0');
      var ty = parseFloat(s.getAttribute('data-fs-ty') || '0');
      s.style.transform = 'translate(' + tx + 'px, ' + ty + 'px) scale(' + sc + ')';
      s.style.transformOrigin = 'center center';
    }

    toolbar.addEventListener('click', function (e) {
      var b = e.target.closest('.mermaid-action[data-fs-act]');
      if (!b) return;
      var a = b.getAttribute('data-fs-act');
      var t = content.querySelector('svg');
      if (!t) return;
      var cur = parseFloat(t.getAttribute('data-fs-scale') || '1');
      if (a === 'zoomIn')  t.setAttribute('data-fs-scale', Math.min(cur + 0.25, 5));
      if (a === 'zoomOut') t.setAttribute('data-fs-scale', Math.max(cur - 0.25, 0.25));
      if (a === 'reset') {
        t.setAttribute('data-fs-scale', '1');
        t.setAttribute('data-fs-tx', '0');
        t.setAttribute('data-fs-ty', '0');
      }
      if (a === 'downloadSvg') {
        var svgString = new XMLSerializer().serializeToString(t);
        downloadFile(svgString, 'mermaid-' + Date.now() + '.svg', 'image/svg+xml');
        showToast('已下载 SVG');
      }
      if (a === 'downloadPng') svgToPng(t, 'mermaid-' + Date.now() + '.png');
      applyFsTransform(t);
    });

    content.addEventListener('wheel', function (e) {
      e.preventDefault();
      var t = content.querySelector('svg');
      if (!t) return;
      var cur = parseFloat(t.getAttribute('data-fs-scale') || '1');
      var delta = e.deltaY > 0 ? -0.15 : 0.15;
      t.setAttribute('data-fs-scale', Math.max(0.25, Math.min(cur + delta, 5)));
      applyFsTransform(t);
    }, { passive: false });

    var dragging = false, sx = 0, sy = 0, stx = 0, sty = 0;
    content.addEventListener('mousedown', function (e) {
      dragging = true; sx = e.clientX; sy = e.clientY;
      var t = content.querySelector('svg');
      if (!t) return;
      stx = parseFloat(t.getAttribute('data-fs-tx') || '0');
      sty = parseFloat(t.getAttribute('data-fs-ty') || '0');
      content.style.cursor = 'grabbing';
    });
    document.addEventListener('mousemove', function (e) {
      if (!dragging) return;
      var t = content.querySelector('svg');
      if (!t) return;
      t.setAttribute('data-fs-tx', (stx + e.clientX - sx).toString());
      t.setAttribute('data-fs-ty', (sty + e.clientY - sy).toString());
      applyFsTransform(t);
    });
    document.addEventListener('mouseup', function () { dragging = false; if (content) content.style.cursor = 'grab'; });
    content.style.cursor = 'grab';

    document.body.appendChild(overlay);
  }

  // ===== 公共 API =====
  var api = {
    configure: function (opts) {
      if (!opts) return;
      if (typeof opts.toast === 'function') TOAST_FN = opts.toast;
      if (typeof opts.escapeHtml === 'function') ESCAPE_HTML_FN = opts.escapeHtml;
      if (typeof opts.safeStorageGet === 'function') SAFE_STORAGE_GET = opts.safeStorageGet;
      if (typeof opts.safeStorageSet === 'function') SAFE_STORAGE_SET = opts.safeStorageSet;
    },
    init: ensureInitialized,
    renderMermaidBlockTemplate: renderMermaidBlockTemplate,
    renderStreamingMermaidBlockTemplate: renderStreamingMermaidBlockTemplate,
    processContainer: processContainer,
    getTheme: getCurrentTheme,
    setTheme: setCurrentTheme,
    copyCode: copyCode,
    zoomIn: zoomIn,
    zoomOut: zoomOut,
    downloadSvg: downloadSvg,
    downloadPng: downloadPng,
    openFullscreen: openFullscreen,
    detectDiagramType: detectDiagramType,
    normalizeMermaidCode: normalizeMermaidCode,
    cleanupBodyErrors: cleanupBodyErrors
  };

  global.MermaidRenderer = api;

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { bindToolbarDelegation(); });
  } else {
    bindToolbarDelegation();
  }
})(window);
