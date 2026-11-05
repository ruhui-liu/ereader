/*
 * 译读 reader engine.
 *
 * Renders one chapter (EPUB spine item / TXT chapter) inside #yd-content and provides:
 *  - paged layout with CSS multi-column (horizontal text: pages side by side, vertical text: pages stacked),
 *  - scroll layout,
 *  - conversion of vertical (tategaki, right-to-left) books into horizontal left-to-right text,
 *  - bilingual display: translations inserted after each paragraph,
 *  - precise reading anchors ("block:offset") that survive font / layout / translation changes,
 *  - search highlighting, footnote popups and internal links.
 *
 * Talks to Android through window.AndroidBridge.postMessage(json); Android calls window.Reader.*.
 */
(function () {
  'use strict';

  var bridge = window.AndroidBridge;
  function post(type, data) {
    var msg = Object.assign({ type: type }, data || {});
    if (bridge && bridge.postMessage) {
      bridge.postMessage(JSON.stringify(msg));
    } else {
      (window.__ydMessages = window.__ydMessages || []).push(msg);
    }
  }

  var html = document.documentElement;
  var viewport = document.getElementById('yd-viewport');
  var content = document.getElementById('yd-content');
  var dynStyle = document.getElementById('yd-dynamic');
  var note = document.getElementById('yd-note');
  var noteBody = document.getElementById('yd-note-body');

  var BLOCK_TAGS = {
    P: 1, H1: 1, H2: 1, H3: 1, H4: 1, H5: 1, H6: 1, LI: 1, BLOCKQUOTE: 1, DD: 1, DT: 1, FIGCAPTION: 1,
    CAPTION: 1, TD: 1, TH: 1, PRE: 1, DIV: 1, SECTION: 1, ARTICLE: 1, ASIDE: 1, HEADER: 1, FOOTER: 1,
    ADDRESS: 1, CENTER: 1, MAIN: 1, NAV: 1, TABLE: 1, FIGURE: 1, UL: 1, OL: 1, DL: 1, TR: 1, SUMMARY: 1,
    DETAILS: 1, HGROUP: 1, BODY: 1
  };
  var SKIP_TAGS = { RT: 1, RP: 1, SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEMPLATE: 1, HEAD: 1, TITLE: 1 };
  var INSIDE_TAGS = { LI: 1, TD: 1, TH: 1, DD: 1, DT: 1, CAPTION: 1, FIGCAPTION: 1, SUMMARY: 1 };
  var HEADING_TAGS = { H1: 1, H2: 1, H3: 1, H4: 1, H5: 1, H6: 1 };
  var VOID_TAGS = 'area|base|br|col|embed|hr|img|input|link|meta|param|source|track|wbr';

  // Vertical presentation forms -> horizontal equivalents (used when converting tategaki to horizontal).
  var VERTICAL_FORMS = {
    '︐': '，', '︑': '、', '︒': '。', '︓': '：', '︔': '；', '︕': '！',
    '︖': '？', '︗': '〖', '︘': '〗', '︙': '…', '︰': '‥', '︱': '—',
    '︲': '–', '︳': '_', '︴': '_', '︵': '（', '︶': '）', '︷': '｛',
    '︸': '｝', '︹': '〔', '︺': '〕', '︻': '【', '︼': '】', '︽': '《',
    '︾': '》', '︿': '〈', '﹀': '〉', '﹁': '「', '﹂': '」', '﹃': '『',
    '﹄': '』', '﹇': '［', '﹈': '］'
  };
  var VERTICAL_FORMS_RE = /[︐-︙︰-﹄﹇﹈]/g;

  var S = {
    settings: {
      fontSize: 19, lineHeight: 1.75, paraSpacing: 0.6, marginH: 22, marginV: 30,
      fontFamily: '', customFontUrl: '', bg: '#ffffff', fg: '#222222', trColor: '#2f6db3', linkColor: '#2f6db3',
      textAlign: 'justify', indent: '2em', override: true, showRuby: true, paged: true, vertical: false,
      forceHorizontal: false, rtl: false, animate: true, trMode: 0, trScale: 0.92, bookLang: '', trLang: 'zh-CN',
      dark: false, zoom: false
    },
    chapter: null,        // { index, url, mime, count }
    page: 0,
    pages: 1,
    offset: 0,            // current translation of #yd-content in px (paged mode)
    W: 0,
    H: 0,
    blocks: [],           // block elements, index = block id
    blockText: [],        // text of each block (for translation)
    texts: [],            // [{ node, b, start, first }] non-whitespace text nodes in document order
    trEls: {},            // block id -> translation element
    loadToken: 0,
    lastAnchor: null,
    pdfPages: [],         // <section class="yd-pdf-page"> elements (PDF original-page mode)
    pdfMarks: [],         // all elements carrying data-pdf-page (both PDF modes)
    reportTimer: 0,
    relayoutTimer: 0,
    ready: false,
    touch: null,
    suppressClick: false
  };

  // ------------------------------------------------------------------ helpers

  function isVertical() { return !!S.settings.vertical; }
  function isPaged() { return !!S.settings.paged; }
  function pageSize() { return isVertical() ? S.H : S.W; }

  function measure() {
    S.W = viewport.clientWidth || window.innerWidth;
    S.H = viewport.clientHeight || window.innerHeight;
  }

  /** Scroll position along the reading direction (scroll mode). */
  function scrollPos() {
    return isVertical() ? -viewport.scrollLeft : viewport.scrollTop;
  }
  function setScrollPos(p) {
    if (isVertical()) viewport.scrollLeft = -p; else viewport.scrollTop = p;
  }
  function scrollMax() {
    return isVertical()
      ? Math.max(0, viewport.scrollWidth - viewport.clientWidth)
      : Math.max(0, viewport.scrollHeight - viewport.clientHeight);
  }

  /** Absolute position (px from the chapter start along the reading direction) of a client rect. */
  function absPos(r) {
    if (isPaged()) return isVertical() ? r.top + S.offset : r.left + S.offset;
    return isVertical() ? (S.W - r.right) + scrollPos() : r.top + scrollPos();
  }

  function rectOfRange(node, offset) {
    var range = document.createRange();
    var len = node.data.length;
    var o = Math.max(0, Math.min(offset, len - 1));
    range.setStart(node, o);
    range.setEnd(node, Math.min(len, o + 1));
    var rects = range.getClientRects();
    for (var i = 0; i < rects.length; i++) {
      if (rects[i].width > 0 || rects[i].height > 0) return rects[i];
    }
    return null;
  }

  function rectOfElement(el) {
    if (!el) return null;
    var rects = el.getClientRects();
    for (var i = 0; i < rects.length; i++) {
      if (rects[i].width > 0 || rects[i].height > 0) return rects[i];
    }
    return null;
  }

  function settle() {
    // Jump any running page animation to its end so geometry queries are exact.
    if (isPaged() && content.style.transition && content.style.transition !== 'none') {
      content.style.transition = 'none';
      void content.offsetWidth;
    }
  }

  function cssEscapeUrl(u) { return String(u).replace(/["\\\n]/g, ''); }

  // ------------------------------------------------------------------ styles

  function buildCss() {
    var s = S.settings, W = S.W, H = S.H, mh = s.marginH, mv = s.marginV;
    var c = '@namespace epub "http://www.idpf.org/2007/ops";';
    // Footnote / endnote bodies are shown in a popup when their reference is tapped.
    c += '#yd-content aside[epub|type~="footnote"],#yd-content aside[epub|type~="endnote"],#yd-content aside[epub|type~="rearnote"],' +
      '#yd-content aside[epub\\:type~="footnote"],#yd-content aside[epub\\:type~="endnote"]{display:none !important;}';
    if (s.customFontUrl) {
      c += '@font-face{font-family:"YDCustom";src:url("' + cssEscapeUrl(s.customFontUrl) + '");font-display:block;}';
    }
    c += 'html,body{background:' + s.bg + ' !important;color:' + s.fg + ' !important;}';
    c += '#yd-content{font-size:' + s.fontSize + 'px !important;color:' + s.fg + ';line-height:' + s.lineHeight + ';}';
    c += '#yd-note{background:' + s.bg + ';color:' + s.fg + ';border:1px solid ' + s.fg + '22;}';
    if (s.fontFamily) {
      c += '#yd-content,#yd-content :not(pre):not(code):not(kbd):not(samp):not(tt),#yd-note{font-family:' + s.fontFamily + ' !important;}';
    }
    if (s.override) {
      c += '#yd-content :is(p,div,li,blockquote,dd,dt,td,th,figcaption,section,article,span,a,em,i,b,strong){line-height:' + s.lineHeight + ' !important;}';
      c += '#yd-content *{color:inherit !important;background-color:transparent !important;border-color:' + s.fg + '33;}';
      c += '#yd-content a,#yd-content a *{color:' + s.linkColor + ' !important;}';
      c += '#yd-content p{margin-block-start:0 !important;margin-block-end:' + s.paraSpacing + 'em !important;text-align:' + s.textAlign + ' !important;}';
      if (s.indent) c += '#yd-content p{text-indent:' + s.indent + ' !important;}';
    } else {
      c += '#yd-content a{color:' + s.linkColor + ';}';
    }
    if (!s.override && s.indent === '0') c += '#yd-content p{text-indent:0 !important;}';
    if (!s.showRuby) c += '#yd-content rt,#yd-content rp{display:none !important;}';

    // Writing direction.
    if (s.forceHorizontal) {
      c += 'html.yd-hz #yd-content,html.yd-hz #yd-content *{writing-mode:horizontal-tb !important;' +
        '-webkit-writing-mode:horizontal-tb !important;text-orientation:mixed !important;' +
        'text-combine-upright:none !important;-webkit-text-combine:none !important;}';
      // Vertical books often size or position blocks in the vertical axis; neutralize the common cases.
      c += 'html.yd-hz #yd-content :is(p,div,h1,h2,h3,h4,h5,h6,section,body){height:auto !important;' +
        'max-height:none !important;}';
    }

    // Layout.
    if (s.paged) {
      if (isVertical()) {
        c += '#yd-content{width:' + W + 'px !important;height:' + H + 'px !important;padding:' + mv + 'px ' + mh + 'px !important;' +
          'column-width:' + (H - 2 * mv) + 'px !important;column-gap:' + (2 * mv) + 'px !important;}';
      } else {
        c += '#yd-content{width:' + W + 'px !important;height:' + H + 'px !important;padding:' + mv + 'px ' + mh + 'px !important;' +
          'column-width:' + (W - 2 * mh) + 'px !important;column-gap:' + (2 * mh) + 'px !important;}';
      }
      c += '#yd-viewport{overflow:hidden !important;}';
    } else {
      if (isVertical()) {
        c += '#yd-viewport{overflow-x:auto !important;overflow-y:hidden !important;}';
        c += '#yd-content{height:' + H + 'px !important;min-width:' + W + 'px;padding:' + mv + 'px ' + mh + 'px !important;}';
      } else {
        c += '#yd-viewport{overflow-y:auto !important;overflow-x:hidden !important;}';
        c += '#yd-content{width:' + W + 'px !important;min-height:' + H + 'px;padding:' + mv + 'px ' + mh + 'px !important;}';
      }
    }
    var maxW = W - 2 * mh, maxH = H - 2 * mv;
    c += '#yd-content img,#yd-content svg,#yd-content video{max-width:' + maxW + 'px !important;max-height:' + maxH + 'px !important;}';
    c += '#yd-content img{height:auto;}';
    // PDF pages: one page per screen in paged mode; page images take the theme's paper colour.
    c += '.yd-pdf-page{break-before:column;text-align:center;margin:0 !important;padding:0 !important;text-indent:0 !important;}';
    c += '.yd-pdf-page:first-child{break-before:auto;}';
    c += 'html.yd-scroll .yd-pdf-page{break-before:auto;margin-bottom:14px !important;}';
    c += '#yd-content .yd-pdf-page,#yd-content .yd-pdf-figure{background-color:' + s.bg + ' !important;}';
    c += '.yd-pdf-figure{margin:0 0 1em !important;text-align:center;text-indent:0 !important;}';
    c += '.yd-pdf-page img,.yd-pdf-figure img{display:block;margin:0 auto;' +
      (s.dark ? 'filter:invert(1) hue-rotate(180deg);mix-blend-mode:screen;' : 'mix-blend-mode:multiply;') + '}';
    c += '.yd-pdf-text>p,.yd-pdf-text>h3{display:none !important;}';
    c += '.yd-pdf-text{text-align:start;}';
    c += '.yd-pdf-mark{display:block;height:0;overflow:hidden;margin:0 !important;}';
    // Translations.
    c += '.yd-tr{color:' + s.trColor + ' !important;font-size:' + s.trScale + 'em !important;line-height:' + s.lineHeight +
      ' !important;text-indent:0 !important;margin-block-start:0.15em !important;margin-block-end:' + (s.paraSpacing + 0.35) + 'em !important;}';
    c += 'html.yd-tr-off .yd-tr{display:none !important;}';
    c += 'html.yd-tr-only [data-yd-done]:not(.yd-inside){display:none !important;}';
    c += 'html.yd-tr-only .yd-inside>.yd-orig{display:none !important;}';
    c += 'html.yd-tr-only .yd-tr{color:' + s.fg + ' !important;font-size:1em !important;}';
    return c;
  }

  function applyHtmlClasses() {
    var s = S.settings;
    var keep = (html.getAttribute('data-yd-book-class') || '').split(/\s+/).filter(Boolean);
    var cls = keep.slice();
    cls.push(s.paged ? 'yd-paged' : 'yd-scroll');
    if (s.forceHorizontal) cls.push('yd-hz');
    if (isVertical()) cls.push('yd-vertical');
    cls.push(s.trMode === 0 ? 'yd-tr-off' : s.trMode === 2 ? 'yd-tr-only' : 'yd-tr-on');
    html.className = cls.join(' ');
    html.style.writingMode = isVertical() ? 'vertical-rl' : 'horizontal-tb';
    content.style.writingMode = isVertical() ? 'vertical-rl' : 'horizontal-tb';
  }

  // ------------------------------------------------------------------ indexing

  function indexContent() {
    var blocks = [], blockText = [], blockRaw = [], texts = [];
    var blockIds = new Map();

    function blockId(el) {
      var b = blockIds.get(el);
      if (b === undefined) {
        b = blocks.length;
        blockIds.set(el, b);
        blocks.push(el);
        blockText.push('');
        blockRaw.push(0);
        if (el !== content) el.setAttribute('data-yd-b', String(b));
      }
      return b;
    }

    function walk(node, blockEl) {
      for (var ch = node.firstChild; ch; ch = ch.nextSibling) {
        if (ch.nodeType === 3) {
          var data = ch.data;
          if (!/\S/.test(data)) {
            var bid = blockIds.get(blockEl);
            if (bid !== undefined) {
              blockRaw[bid] += data.length;
              if (!/\s$/.test(blockText[bid])) blockText[bid] += ' ';
            }
            continue;
          }
          var b = blockId(blockEl);
          texts.push({ node: ch, b: b, start: blockRaw[b], first: data.search(/\S/) });
          blockRaw[b] += data.length;
          blockText[b] += data;
        } else if (ch.nodeType === 1) {
          var tag = ch.tagName.toUpperCase();
          if (SKIP_TAGS[tag]) continue;
          var cl = ch.classList;
          if (cl && (cl.contains('yd-tr') || cl.contains('yd-ui') || ch.id === 'yd-chapter-footer')) continue;
          if (tag === 'BR') {
            var b2 = blockIds.get(blockEl);
            if (b2 !== undefined) blockText[b2] += '\n';
            continue;
          }
          walk(ch, BLOCK_TAGS[tag] ? ch : blockEl);
        }
      }
    }
    walk(content, content);

    S.blocks = blocks;
    S.blockText = blockText.map(function (t) {
      return t.replace(/[ \t\r\f\v\u00A0]+/g, ' ').replace(/ *\n */g, '\n').replace(/^\s+|\s+$/g, '');
    });
    S.texts = texts;
  }

  // ------------------------------------------------------------------ anchors

  /** Position of the text node entry i (uses the first visible char), or null when not rendered. */
  function textPos(i) {
    var t = S.texts[i];
    var r = rectOfRange(t.node, t.first);
    if (r) return absPos(r);
    // Hidden (e.g. original text in translation-only mode): fall back to its translation element.
    var tr = S.trEls[t.b];
    var rr = rectOfElement(tr);
    return rr ? absPos(rr) : null;
  }

  /** First text position at or after `start` (px along reading direction). */
  function firstTextIndexFrom(start) {
    var n = S.texts.length;
    if (!n) return -1;
    var lo = 0, hi = n - 1, ans = -1;
    while (lo <= hi) {
      var mid = (lo + hi) >> 1;
      var j = mid, p = null;
      while (j <= hi && (p = textPos(j)) === null) j++;
      if (p === null) { hi = mid - 1; continue; }
      if (p >= start - 1) { ans = j; hi = mid - 1; } else { lo = j + 1; }
    }
    return ans;
  }

  function currentStart() {
    if (isPaged()) return S.page * pageSize();
    return scrollPos() + (isVertical() ? S.settings.marginH : S.settings.marginV) * 0.5;
  }

  /** Extent of a PDF page section along the reading direction: { s: start, e: end }. */
  function sectionSpan(el) {
    var rects = el.getClientRects();
    if (!rects.length) return null;
    var first = rects[0], last = rects[rects.length - 1];
    var s0 = absPos(first);
    var ext = isPaged() ? (isVertical() ? last.height : last.width) : (isVertical() ? last.width : last.height);
    return { s: s0, e: Math.max(absPos(last) + ext, s0 + 1) };
  }

  /** Lead before the first content of a screen page / scroll position. */
  function leadIn() {
    if (isPaged()) return (isVertical() ? S.settings.marginV : S.settings.marginH) + 2;
    return (isVertical() ? S.settings.marginH : S.settings.marginV) + 2;
  }

  /** PDF original-page mode: the page at the top of the view, plus how far into it we are. */
  function capturePageAnchor() {
    settle();
    var start = currentStart();
    var lim = start + leadIn();
    var cur = null, span = null;
    for (var i = 0; i < S.pdfPages.length; i++) {
      var sp = sectionSpan(S.pdfPages[i]);
      if (!sp) continue;
      if (cur === null || sp.s <= lim) { cur = S.pdfPages[i]; span = sp; }
      if (sp.s > lim) break;
    }
    if (!cur) return null;
    var f = Math.max(0, Math.min(1, (start - span.s) / (span.e - span.s)));
    var bEl = cur.querySelector('[data-yd-b]');
    return { p: +cur.getAttribute('data-pdf-page'), f: f, b: bEl ? +bEl.getAttribute('data-yd-b') : 0, o: 0 };
  }

  /** Captures the first visible character as { b: block, o: offset } (or a PDF page anchor). */
  function captureAnchor() {
    if (S.pdfPages.length) return capturePageAnchor();
    if (!S.texts.length) return null;
    settle();
    var start = currentStart();
    var i = firstTextIndexFrom(start);
    if (i < 0) i = S.texts.length - 1;
    var t = S.texts[i];
    var node = t.node, len = node.data.length;
    // Within the node, binary-search the first char at/after start.
    var lo = t.first, hi = len - 1, best = t.first;
    var p0 = rectOfRange(node, lo);
    if (p0 && absPos(p0) < start - 1) {
      while (lo <= hi) {
        var mid = (lo + hi) >> 1;
        var r = rectOfRange(node, mid);
        if (!r) { lo = mid + 1; continue; }
        if (absPos(r) >= start - 1) { best = mid; hi = mid - 1; } else { lo = mid + 1; }
      }
    }
    return { b: t.b, o: t.start + best };
  }

  function anchorString(a) {
    if (!a) return null;
    if (a.p !== undefined) return 'p' + a.p + ':' + a.f.toFixed(4);
    return a.b + ':' + a.o;
  }
  function parseAnchor(s) {
    if (!s) return null;
    var pm = /^p(\d+):([\d.]+)$/.exec(String(s));
    if (pm) return { p: +pm[1], f: +pm[2], b: 0, o: 0 };
    var m = /^(\d+):(\d+)$/.exec(String(s));
    return m ? { b: +m[1], o: +m[2] } : null;
  }

  /** Absolute position of an anchor, or null. */
  function anchorPos(a) {
    if (!a) return null;
    if (a.p !== undefined) {
      var pel = content.querySelector('[data-pdf-page="' + a.p + '"]');
      if (!pel) return null;
      if (pel.classList.contains('yd-pdf-page')) {
        var sp = sectionSpan(pel);
        return sp ? sp.s + (a.f || 0) * (sp.e - sp.s) : null;
      }
      var pr = rectOfElement(pel) || pel.getBoundingClientRect();
      return pr ? absPos(pr) : null;
    }
    var cand = null;
    for (var i = 0; i < S.texts.length; i++) {
      var t = S.texts[i];
      if (t.b < a.b) continue;
      if (t.b > a.b) break;
      if (t.start <= a.o) cand = t; else break;
    }
    if (cand) {
      var off = Math.max(cand.first, a.o - cand.start);
      var r = rectOfRange(cand.node, off);
      if (r) return absPos(r);
    }
    var el = S.trEls[a.b] || S.blocks[a.b];
    var rr = rectOfElement(el);
    return rr ? absPos(rr) : null;
  }

  function goToPos(pos, animate) {
    if (pos === null || pos === undefined) return;
    if (isPaged()) {
      goToPage(Math.floor(Math.max(0, pos) / pageSize()), animate);
    } else {
      var lead = isVertical() ? S.settings.marginH : S.settings.marginV;
      setScrollPos(Math.max(0, pos - lead));
    }
  }

  // ------------------------------------------------------------------ pagination

  function computePages() {
    if (!isPaged()) { S.pages = 1; return; }
    settle();
    var size = pageSize();
    var end = document.getElementById('yd-end');
    var r = rectOfElement(end) || (end && end.getBoundingClientRect());
    var endPage = r ? Math.floor(Math.max(0, absPos(r) - 1) / size) : 0;
    // The sentinel may start a fresh, otherwise empty column; find the last rendered content.
    var contentPos = null;
    for (var i = S.texts.length - 1, n = 0; i >= 0 && n < 200; i--, n++) {
      var t = S.texts[i];
      var rt = rectOfRange(t.node, t.node.data.length - 1) || rectOfRange(t.node, t.first);
      if (rt) { contentPos = absPos(rt); break; }
    }
    var tail = content.querySelectorAll('img, svg, #yd-chapter-footer, .yd-tr');
    for (var k = Math.max(0, tail.length - 3); k < tail.length; k++) {
      var re = rectOfElement(tail[k]);
      if (re) {
        var pe = absPos(re) + (isVertical() ? re.height : re.width) - 1;
        contentPos = contentPos === null ? pe : Math.max(contentPos, absPos(re));
      }
    }
    var pages = endPage + 1;
    if (contentPos !== null) {
      var contentPage = Math.floor(Math.max(0, contentPos) / size);
      if (endPage === contentPage + 1) pages = contentPage + 1;
    }
    S.pages = Math.max(1, pages);
  }

  function setOffset(off, animate) {
    S.offset = off;
    var anim = animate && S.settings.animate && !isVertical();
    content.style.transition = anim ? 'transform 220ms cubic-bezier(.2,.7,.3,1)' : 'none';
    content.style.transform = isVertical()
      ? 'translate3d(0,' + (-off) + 'px,0)'
      : 'translate3d(' + (-off) + 'px,0,0)';
  }

  function goToPage(n, animate) {
    n = Math.max(0, Math.min(n, S.pages - 1));
    S.page = n;
    setOffset(n * pageSize(), animate);
    scheduleReport();
  }

  function atEnd() {
    if (isPaged()) return S.page >= S.pages - 1;
    return scrollPos() >= scrollMax() - 2;
  }
  function atStart() {
    if (isPaged()) return S.page <= 0;
    return scrollPos() <= 2;
  }

  function next() {
    hideNote();
    if (!S.ready) return;
    if (atEnd()) { post('boundary', { dir: 'next' }); return; }
    if (isPaged()) {
      goToPage(S.page + 1, true);
    } else {
      var step = (isVertical() ? viewport.clientWidth : viewport.clientHeight) - 2 * S.settings.lineHeight * S.settings.fontSize;
      viewport.scrollBy(isVertical() ? { left: -step, behavior: 'smooth' } : { top: step, behavior: 'smooth' });
    }
  }

  function prev() {
    hideNote();
    if (!S.ready) return;
    if (atStart()) { post('boundary', { dir: 'prev' }); return; }
    if (isPaged()) {
      goToPage(S.page - 1, true);
    } else {
      var step = (isVertical() ? viewport.clientWidth : viewport.clientHeight) - 2 * S.settings.lineHeight * S.settings.fontSize;
      viewport.scrollBy(isVertical() ? { left: step, behavior: 'smooth' } : { top: -step, behavior: 'smooth' });
    }
  }

  /** Re-lays out the chapter and returns to the given anchor (or the current one). */
  function relayout(anchor) {
    if (!S.chapter) return;
    var a = anchor !== undefined ? anchor : (S.ready ? captureAnchor() : null);
    measure();
    applyHtmlClasses();
    dynStyle.textContent = buildCss();
    updateFooter();
    void content.offsetWidth;
    computePages();
    if (a) {
      goToPos(anchorPos(a), false);
    } else if (isPaged()) {
      goToPage(Math.min(S.page, S.pages - 1), false);
    }
    scheduleReport();
  }

  function scheduleRelayout() {
    clearTimeout(S.relayoutTimer);
    S.relayoutTimer = setTimeout(function () { relayout(S.lastAnchor || undefined); }, 120);
  }

  // ------------------------------------------------------------------ position reporting

  function fraction() {
    if (isPaged()) return S.pages > 1 ? S.page / (S.pages - 1) : 0;
    var m = scrollMax();
    return m > 0 ? Math.min(1, scrollPos() / m) : 0;
  }

  /**
   * 0-based PDF page shown on screen (-1 for non-PDF books): the first page that starts on the visible screen,
   * or else the page continuing from before it.
   */
  function currentPdfPage(a) {
    if (a && a.p !== undefined) return a.p;
    if (!S.pdfMarks.length) return -1;
    settle();
    var from, to;
    if (isPaged()) { from = S.page * pageSize(); to = from + pageSize(); }
    else { from = scrollPos(); to = from + (isVertical() ? viewport.clientWidth : viewport.clientHeight); }
    var before = +S.pdfMarks[0].getAttribute('data-pdf-page');
    for (var i = 0; i < S.pdfMarks.length; i++) {
      var el = S.pdfMarks[i];
      var r = rectOfElement(el) || el.getBoundingClientRect();
      if (!r) continue;
      var pos = absPos(r);
      if (pos >= from - 1 && pos < to) return +el.getAttribute('data-pdf-page');
      if (pos < from) before = +el.getAttribute('data-pdf-page'); else break;
    }
    return before;
  }

  function excerptAt(a) {
    if (!a) return '';
    if (a.p !== undefined) {
      var txt = (S.blockText[a.b] || '').replace(/\s+/g, ' ').trim().substring(0, 60);
      return '第 ' + (a.p + 1) + ' 页' + (txt ? '  ' + txt : '');
    }
    var t = S.blockText[a.b] || '';
    // Offsets are in raw text; normalized text is close enough for an excerpt.
    var start = Math.max(0, Math.min(t.length, a.o - 2));
    var s = t.substring(start, start + 80);
    if (s.length < 20) s = t.substring(0, 80);
    return s.replace(/\s+/g, ' ').trim();
  }

  function scheduleReport() {
    clearTimeout(S.reportTimer);
    S.reportTimer = setTimeout(report, 260);
  }

  function report() {
    if (!S.chapter || !S.ready) return;
    var a = captureAnchor();
    S.lastAnchor = a;
    var page = S.page, pages = S.pages;
    if (!isPaged()) {
      var vp = isVertical() ? viewport.clientWidth : viewport.clientHeight;
      var total = scrollMax() + vp;
      pages = Math.max(1, Math.ceil(total / vp));
      page = Math.min(pages - 1, Math.round(scrollPos() / vp));
    }
    post('position', {
      index: S.chapter.index,
      page: page,
      pages: pages,
      fraction: fraction(),
      anchor: anchorString(a),
      block: a ? a.b : 0,
      excerpt: excerptAt(a),
      pdfPage: currentPdfPage(a)
    });
  }

  // ------------------------------------------------------------------ chapter loading

  function decodeBytes(buf, mime) {
    var bytes = new Uint8Array(buf);
    var enc = 'utf-8';
    if (bytes.length >= 2 && bytes[0] === 0xFF && bytes[1] === 0xFE) enc = 'utf-16le';
    else if (bytes.length >= 2 && bytes[0] === 0xFE && bytes[1] === 0xFF) enc = 'utf-16be';
    else if (!(bytes.length >= 3 && bytes[0] === 0xEF && bytes[1] === 0xBB && bytes[2] === 0xBF)) {
      var head = '';
      for (var i = 0; i < Math.min(bytes.length, 1024); i++) head += String.fromCharCode(bytes[i]);
      var m = /encoding\s*=\s*["']([A-Za-z0-9_\-]+)["']/i.exec(head) || /charset\s*=\s*["']?([A-Za-z0-9_\-]+)/i.exec(head);
      if (m) enc = m[1].toLowerCase();
    }
    try {
      return new TextDecoder(enc).decode(bytes);
    } catch (e) {
      return new TextDecoder('utf-8').decode(bytes);
    }
  }

  function parseDocument(text, mime) {
    var doc = null;
    if (/xml/.test(mime || '') || /^\s*<\?xml/.test(text)) {
      try {
        doc = new DOMParser().parseFromString(text, 'application/xhtml+xml');
        if (doc.getElementsByTagName('parsererror').length) doc = null;
      } catch (e) { doc = null; }
    }
    if (!doc) {
      var fixed = text
        .replace(/^\s*<\?xml[^>]*>/, '')
        .replace(new RegExp('<(?!(?:' + VOID_TAGS + ')\\b)([a-zA-Z][\\w:.-]*)((?:\\s[^<>]*?)?)\\s*/>', 'g'), '<$1$2></$1>');
      doc = new DOMParser().parseFromString(fixed, 'text/html');
    }
    return doc;
  }

  function absUrl(u, base) {
    try { return new URL(u, base).href; } catch (e) { return u; }
  }

  function rewriteCssUrls(css, base) {
    return css.replace(/url\(\s*(['"]?)([^'")]+)\1\s*\)/g, function (m, q, u) {
      if (/^(data:|https?:|blob:)/i.test(u)) return m;
      return 'url("' + absUrl(u, base) + '")';
    }).replace(/@import\s+(['"])([^'"]+)\1/g, function (m, q, u) {
      return '@import "' + absUrl(u, base) + '"';
    });
  }

  function fixUrls(root, base) {
    var XLINK = 'http://www.w3.org/1999/xlink';
    root.querySelectorAll('[src]').forEach(function (el) {
      el.setAttribute('src', absUrl(el.getAttribute('src'), base));
    });
    root.querySelectorAll('image, use').forEach(function (el) {
      var h = el.getAttributeNS(XLINK, 'href');
      if (h) el.setAttributeNS(XLINK, 'xlink:href', absUrl(h, base));
      var h2 = el.getAttribute('href');
      if (h2) el.setAttribute('href', absUrl(h2, base));
    });
    root.querySelectorAll('a[href], area[href]').forEach(function (el) {
      var h = el.getAttribute('href');
      el.setAttribute('data-yd-href', h);
      el.setAttribute('href', absUrl(h, base));
    });
    root.querySelectorAll('[style]').forEach(function (el) {
      var st = el.getAttribute('style');
      if (/url\(/i.test(st)) el.setAttribute('style', rewriteCssUrls(st, base));
    });
    root.querySelectorAll('script').forEach(function (el) { el.remove(); });
    // Inline event handlers are never needed by the reader.
    root.querySelectorAll('*').forEach(function (el) {
      for (var i = el.attributes.length - 1; i >= 0; i--) {
        var n = el.attributes[i].name;
        if (n.length > 2 && n.charAt(0) === 'o' && n.charAt(1) === 'n') el.removeAttribute(n);
      }
    });
  }

  function loadBookStyles(doc, base) {
    document.querySelectorAll('[data-yd-book-style]').forEach(function (el) { el.remove(); });
    var waits = [];
    var nodes = doc.querySelectorAll('link, style');
    nodes.forEach(function (el) {
      var tag = el.tagName.toLowerCase();
      if (tag === 'link') {
        var rel = (el.getAttribute('rel') || '').toLowerCase();
        var href = el.getAttribute('href');
        if (rel.indexOf('stylesheet') < 0 || !href || rel.indexOf('alternate') >= 0) return;
        var link = document.createElement('link');
        link.rel = 'stylesheet';
        link.href = absUrl(href, base);
        link.setAttribute('data-yd-book-style', '1');
        waits.push(new Promise(function (res) { link.onload = res; link.onerror = res; }));
        document.head.insertBefore(link, dynStyle);
      } else {
        var st = document.createElement('style');
        st.setAttribute('data-yd-book-style', '1');
        st.textContent = rewriteCssUrls(el.textContent || '', base);
        document.head.insertBefore(st, dynStyle);
      }
    });
    return waits;
  }

  function convertVerticalForms(root) {
    var w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    var n;
    while ((n = w.nextNode())) {
      if (VERTICAL_FORMS_RE.test(n.data)) {
        VERTICAL_FORMS_RE.lastIndex = 0;
        n.data = n.data.replace(VERTICAL_FORMS_RE, function (ch) { return VERTICAL_FORMS[ch] || ch; });
      }
      VERTICAL_FORMS_RE.lastIndex = 0;
    }
  }

  function withTimeout(promises, ms) {
    return Promise.race([
      Promise.all(promises),
      new Promise(function (res) { setTimeout(res, ms); })
    ]);
  }

  function updateFooter() {
    var f = document.getElementById('yd-chapter-footer');
    if (!f) return;
    var last = S.chapter && S.chapter.index >= S.chapter.count - 1;
    f.textContent = last ? '— 全书完 —' : (isPaged() ? '' : '— 本章完 · 点击进入下一章 —');
    f.style.display = (isPaged() && !last) ? 'none' : 'block';
  }

  function loadChapter(msg) {
    var token = ++S.loadToken;
    S.ready = false;
    hideNote();
    clearSearch();
    content.classList.remove('yd-ready');
    var url = absUrl(msg.url, location.href);
    S.chapter = { index: msg.index, url: url, mime: msg.mime || '', count: msg.count || 1 };

    return fetch(url).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.arrayBuffer();
    }).then(function (buf) {
      if (token !== S.loadToken) return;
      var doc = parseDocument(decodeBytes(buf, msg.mime), msg.mime);
      var body = doc.body || doc.getElementsByTagName('body')[0] || doc.documentElement;
      var waits = loadBookStyles(doc, url);
      fixUrls(body, url);

      var bookHtmlClass = (doc.documentElement && doc.documentElement.getAttribute('class')) || '';
      html.setAttribute('data-yd-book-class', bookHtmlClass);
      document.body.className = body.getAttribute ? (body.getAttribute('class') || '') : '';
      var lang = (doc.documentElement && (doc.documentElement.getAttribute('lang') ||
        doc.documentElement.getAttributeNS('http://www.w3.org/XML/1998/namespace', 'lang'))) ||
        (body.getAttribute && body.getAttribute('lang')) || S.settings.bookLang || '';
      content.setAttribute('lang', lang);

      content.textContent = '';
      S.trEls = {};
      var frag = document.createDocumentFragment();
      Array.prototype.slice.call(body.childNodes).forEach(function (n) {
        frag.appendChild(document.importNode(n, true));
      });
      content.appendChild(frag);
      var footer = document.createElement('div');
      footer.id = 'yd-chapter-footer';
      footer.className = 'yd-ui';
      content.appendChild(footer);
      var end = document.createElement('span');
      end.id = 'yd-end';
      end.className = 'yd-end yd-ui';
      end.textContent = '\u200B';
      content.appendChild(end);

      if (S.settings.forceHorizontal) convertVerticalForms(content);
      indexContent();
      S.pdfPages = Array.prototype.slice.call(content.querySelectorAll('section.yd-pdf-page'));
      S.pdfMarks = Array.prototype.slice.call(content.querySelectorAll('[data-pdf-page]'));

      var imgs = Array.prototype.slice.call(content.querySelectorAll('img'));
      imgs.forEach(function (img) {
        // Images with known dimensions (e.g. PDF pages) don't change the layout when they arrive.
        var sized = img.hasAttribute('width') && img.hasAttribute('height');
        if (!img.complete && !sized) {
          waits.push(new Promise(function (res) { img.addEventListener('load', res); img.addEventListener('error', res); }));
          img.addEventListener('load', function () { if (S.ready && token === S.loadToken) scheduleRelayout(); });
        }
      });
      // Measure with final styles applied.
      measure();
      applyHtmlClasses();
      dynStyle.textContent = buildCss();
      var fontsReady = document.fonts && document.fonts.ready ? document.fonts.ready : Promise.resolve();
      return withTimeout(waits.concat([fontsReady]), 1500);
    }).then(function () {
      if (token !== S.loadToken) return;
      S.page = 0;
      setOffset(0, false);
      if (!isPaged()) setScrollPos(0);
      relayout(null);
      S.ready = true;
      positionAt(msg.position || { type: 'start' });
      content.classList.add('yd-ready');
      post('chapterLoaded', { index: S.chapter.index, pages: S.pages, blocks: S.blocks.length });
      report();
    }).catch(function (e) {
      if (token !== S.loadToken) return;
      content.textContent = '';
      var p = document.createElement('p');
      p.textContent = '无法加载本章：' + (e && e.message ? e.message : e);
      content.appendChild(p);
      content.classList.add('yd-ready');
      S.ready = true;
      post('error', { message: String(e && e.message ? e.message : e) });
    });
  }

  function positionAt(p) {
    var t = p && p.type;
    if (t === 'end') {
      if (isPaged()) goToPage(S.pages - 1, false); else setScrollPos(scrollMax());
    } else if (t === 'anchor') {
      var a = parseAnchor(p.anchor);
      var pos = anchorPos(a);
      if (pos !== null) goToPos(pos, false);
      else goToFraction(p.fraction || 0);
    } else if (t === 'fraction') {
      goToFraction(p.value || 0);
    } else if (t === 'fragment') {
      var el = p.id ? findById(p.id) : null;
      var r = rectOfElement(el) || (el && el.getBoundingClientRect());
      if (r) goToPos(absPos(r), false);
    } else if (t === 'search') {
      highlightSearch(p.query, p.occurrence || 0);
    }
  }

  function findById(id) {
    if (!id) return null;
    var el = document.getElementById(id);
    if (el && content.contains(el)) return el;
    try {
      return content.querySelector('[id="' + CSS.escape(id) + '"], [name="' + CSS.escape(id) + '"]');
    } catch (e) { return null; }
  }

  function goToFraction(f) {
    f = Math.max(0, Math.min(1, f || 0));
    if (isPaged()) goToPage(Math.round(f * (S.pages - 1)), false);
    else setScrollPos(f * scrollMax());
    scheduleReport();
  }

  // ------------------------------------------------------------------ translation

  function requestBlocks() {
    if (!S.chapter || !S.ready) return;
    var items = [];
    for (var i = 0; i < S.blockText.length; i++) {
      if (S.blockText[i]) items.push({ i: i, t: S.blockText[i] });
    }
    var a = S.lastAnchor || captureAnchor();
    post('blocks', { index: S.chapter.index, items: items, center: a ? a.b : 0 });
  }

  /** items: [{ i: blockId, t: translation }] */
  function setTranslations(index, items) {
    if (!S.chapter || index !== S.chapter.index || !items || !items.length) return;
    var a = S.ready ? captureAnchor() : null;
    items.forEach(function (it) {
      var el = S.blocks[it.i];
      if (!el || typeof it.t !== 'string') return;
      var tr = S.trEls[it.i];
      if (!tr) {
        tr = document.createElement('div');
        tr.className = 'yd-tr';
        tr.setAttribute('lang', S.settings.trLang || 'zh-CN');
        tr.setAttribute('data-yd-for', String(it.i));
        var tag = el === content ? 'BODY' : el.tagName.toUpperCase();
        if (INSIDE_TAGS[tag] || el === content) {
          var wrap = document.createElement('span');
          wrap.className = 'yd-orig';
          while (el.firstChild) wrap.appendChild(el.firstChild);
          el.appendChild(wrap);
          el.appendChild(tr);
          if (el !== content) el.classList.add('yd-inside');
        } else {
          if (HEADING_TAGS[tag]) tr.classList.add('yd-tr-h');
          el.parentNode.insertBefore(tr, el.nextSibling);
        }
        S.trEls[it.i] = tr;
      }
      tr.textContent = it.t;
      if (el !== content) el.setAttribute('data-yd-done', '1');
    });
    if (S.ready) {
      computePages();
      if (a) goToPos(anchorPos(a), false);
      S.lastAnchor = a;
      scheduleReport();
    }
  }

  function clearTranslations() {
    var a = S.ready ? captureAnchor() : null;
    Object.keys(S.trEls).forEach(function (k) {
      var tr = S.trEls[k];
      var el = S.blocks[+k];
      if (tr && tr.parentNode) tr.parentNode.removeChild(tr);
      if (el && el !== content) {
        el.removeAttribute('data-yd-done');
        el.classList.remove('yd-inside');
      }
      var wrap = el && el.querySelector(':scope > .yd-orig');
      if (wrap) {
        while (wrap.firstChild) el.insertBefore(wrap.firstChild, wrap);
        wrap.remove();
      }
    });
    S.trEls = {};
    if (S.ready) relayout(a);
  }

  // ------------------------------------------------------------------ search

  function clearSearch() {
    if (window.CSS && CSS.highlights) CSS.highlights.delete('yd-search');
    document.querySelectorAll('mark.yd-search').forEach(function (m) {
      var p = m.parentNode;
      while (m.firstChild) p.insertBefore(m.firstChild, m);
      p.removeChild(m);
      p.normalize();
    });
  }

  function highlightSearch(query, occurrence) {
    clearSearch();
    if (!query) return false;
    var nodes = [], text = '';
    var w = document.createTreeWalker(content, NodeFilter.SHOW_TEXT, {
      acceptNode: function (n) {
        var p = n.parentElement;
        if (!p || p.closest('rt,rp,script,style,.yd-tr,.yd-ui')) return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_ACCEPT;
      }
    });
    var n;
    while ((n = w.nextNode())) { nodes.push({ node: n, start: text.length }); text += n.data; }
    var hay = text.toLowerCase(), needle = String(query).toLowerCase();
    var idx = -1, from = 0, count = 0, first = -1;
    while ((from = hay.indexOf(needle, from)) >= 0) {
      if (first < 0) first = from;
      if (count === occurrence) { idx = from; break; }
      count++;
      from += needle.length;
    }
    if (idx < 0) idx = first;
    if (idx < 0) return false;
    function locate(off) {
      for (var i = nodes.length - 1; i >= 0; i--) {
        if (nodes[i].start <= off) return { node: nodes[i].node, offset: off - nodes[i].start };
      }
      return null;
    }
    var s = locate(idx), e = locate(idx + needle.length - 1);
    if (!s || !e) return false;
    var range = document.createRange();
    range.setStart(s.node, s.offset);
    range.setEnd(e.node, Math.min(e.node.data.length, e.offset + 1));
    if (window.CSS && CSS.highlights && window.Highlight) {
      CSS.highlights.set('yd-search', new Highlight(range));
    } else if (s.node === e.node) {
      var mark = document.createElement('mark');
      mark.className = 'yd-search';
      try { range.surroundContents(mark); } catch (err) { /* ignore */ }
    }
    var rects = range.getClientRects();
    if (rects.length) goToPos(absPos(rects[0]), false);
    return true;
  }

  // ------------------------------------------------------------------ links & footnotes

  function isNoteRef(a) {
    var t = (a.getAttributeNS('http://www.idpf.org/2007/ops', 'type') || a.getAttribute('epub:type') || '') + ' ' +
      (a.getAttribute('role') || '') + ' ' + (a.className && a.className.baseVal === undefined ? a.className : '');
    if (/noteref|footnote|endnote|doc-noteref|\bnote\b|fnref|\bfn\b/i.test(t)) return true;
    var txt = (a.textContent || '').trim();
    return txt.length > 0 && txt.length <= 6 && /^[\[(（〔［]?[*※†‡]?\s*(\d+|[ivx]+|[a-z]|注\s*\d*|\*+)[\])）〕］]?$/i.test(txt);
  }

  function showNote(el) {
    if (!el) return;
    var clone = el.cloneNode(true);
    clone.querySelectorAll('a[href]').forEach(function (x) {
      if (/^(↩|返回|back|\^|↑)/i.test((x.textContent || '').trim())) x.remove();
    });
    clone.querySelectorAll('[id]').forEach(function (x) { x.removeAttribute('id'); });
    noteBody.textContent = '';
    noteBody.appendChild(clone);
    note.hidden = false;
  }

  function hideNote() { if (!note.hidden) note.hidden = true; }

  function chapterPathOf(url) {
    var u = new URL(url);
    var prefix = '/book/';
    if (u.origin !== location.origin || u.pathname.indexOf(prefix) !== 0) return null;
    return decodeURIComponent(u.pathname.substring(prefix.length));
  }

  function handleLink(a) {
    var href = a.getAttribute('href');
    var u;
    try { u = new URL(href, S.chapter.url); } catch (e) { return; }
    if (u.origin !== location.origin) {
      if (/^(https?|mailto):$/.test(u.protocol)) post('external', { url: u.href });
      return;
    }
    var frag = u.hash ? decodeURIComponent(u.hash.substring(1)) : '';
    var cur = new URL(S.chapter.url);
    var noteRef = isNoteRef(a);
    if (u.pathname === cur.pathname) {
      var target = findById(frag);
      if (!target) return;
      if (noteRef) { showNote(target.closest('aside, li, div, p') || target); return; }
      var r = rectOfElement(target) || target.getBoundingClientRect();
      goToPos(absPos(r), false);
      return;
    }
    var path = chapterPathOf(u.href);
    if (!path) return;
    if (noteRef && frag) {
      // Footnote in another document: fetch it and show it in a popup instead of navigating away.
      fetch(u.href).then(function (r) { return r.arrayBuffer(); }).then(function (buf) {
        var doc = parseDocument(decodeBytes(buf, ''), /\.x?html?$/i.test(u.pathname) && /\.xhtml$/i.test(u.pathname) ? 'application/xhtml+xml' : '');
        var el = doc.getElementById(frag) || doc.querySelector('[name="' + CSS.escape(frag) + '"]');
        if (el) {
          fixUrls(el, u.href);
          showNote(document.importNode(el.closest('aside, li, div, p') || el, true));
        } else {
          post('link', { path: path, fragment: frag });
        }
      }).catch(function () { post('link', { path: path, fragment: frag }); });
      return;
    }
    post('link', { path: path, fragment: frag });
  }

  // ------------------------------------------------------------------ input

  function applyZoom() {
    var meta = document.querySelector('meta[name=viewport]');
    var z = !!S.settings.zoom;
    if (meta) {
      meta.setAttribute('content', 'width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=' + (z ? '5' : '1') +
        ', user-scalable=' + (z ? 'yes' : 'no') + ', viewport-fit=cover');
    }
    viewport.style.touchAction = z ? 'pan-x pan-y pinch-zoom' : '';
  }

  /** True while the user has pinch-zoomed into a PDF page: taps and swipes then pan instead of turning pages. */
  function zoomed() { return !!(window.visualViewport && window.visualViewport.scale > 1.05); }

  function onClick(e) {
    if (S.suppressClick) { S.suppressClick = false; e.preventDefault(); e.stopPropagation(); return; }
    if (zoomed()) return;
    var sel = window.getSelection();
    if (S.touch && S.touch.hadSelection) return;
    if (sel && !sel.isCollapsed) return;
    if (!note.hidden) {
      if (!note.contains(e.target)) { e.preventDefault(); hideNote(); }
      return;
    }
    var target = e.target && e.target.nodeType === 1 ? e.target : (e.target && e.target.parentElement);
    var a = target && target.closest ? target.closest('a[href]') : null;
    if (a && content.contains(a)) {
      e.preventDefault();
      handleLink(a);
      return;
    }
    var footer = target && target.closest ? target.closest('#yd-chapter-footer') : null;
    if (footer && !isPaged()) { post('boundary', { dir: 'next' }); return; }
    var x = e.clientX / window.innerWidth;
    if (x < 0.3) { if (S.settings.rtl) next(); else prev(); }
    else if (x > 0.7) { if (S.settings.rtl) prev(); else next(); }
    else post('menu');
  }

  function onTouchStart(e) {
    if (e.touches.length !== 1) { S.touch = null; return; }
    var t = e.touches[0];
    var sel = window.getSelection();
    S.touch = { x: t.clientX, y: t.clientY, time: Date.now(), dragging: false, dx: 0, hadSelection: !!(sel && !sel.isCollapsed) };
  }

  function onTouchMove(e) {
    var T = S.touch;
    if (!T || !isPaged() || !S.ready || !note.hidden) return;
    if (e.touches.length !== 1 || zoomed()) { T.dragging = false; S.touch = null; return; }
    var t = e.touches[0];
    var dx = t.clientX - T.x, dy = t.clientY - T.y;
    var sel = window.getSelection();
    if (T.hadSelection || (sel && !sel.isCollapsed)) { T.dragging = false; return; }
    if (!T.dragging) {
      if (Math.abs(dx) > 12 && Math.abs(dx) > Math.abs(dy) * 1.2) T.dragging = true; else return;
    }
    e.preventDefault();
    T.dx = dx;
    if (!isVertical()) {
      var resist = (dx > 0 && S.page === 0) || (dx < 0 && S.page === S.pages - 1) ? 0.35 : 1;
      content.style.transition = 'none';
      content.style.transform = 'translate3d(' + (-S.offset + dx * resist) + 'px,0,0)';
    }
  }

  function onTouchEnd() {
    var T = S.touch;
    S.touch = null;
    if (!T || !T.dragging) return;
    S.suppressClick = true;
    setTimeout(function () { S.suppressClick = false; }, 400);
    var dt = Math.max(1, Date.now() - T.time);
    var fast = Math.abs(T.dx) / dt > 0.35;
    if (Math.abs(T.dx) > Math.min(80, S.W * 0.18) || (fast && Math.abs(T.dx) > 24)) {
      var forward = S.settings.rtl ? T.dx > 0 : T.dx < 0;
      if (forward) {
        if (S.page < S.pages - 1) goToPage(S.page + 1, true); else { setOffset(S.offset, true); post('boundary', { dir: 'next' }); }
      } else {
        if (S.page > 0) goToPage(S.page - 1, true); else { setOffset(S.offset, true); post('boundary', { dir: 'prev' }); }
      }
    } else {
      setOffset(S.offset, true);
    }
  }

  document.addEventListener('click', onClick, true);
  viewport.addEventListener('touchstart', onTouchStart, { passive: true });
  viewport.addEventListener('touchmove', onTouchMove, { passive: false });
  viewport.addEventListener('touchend', onTouchEnd, { passive: true });
  viewport.addEventListener('touchcancel', onTouchEnd, { passive: true });
  viewport.addEventListener('scroll', function () { if (!isPaged() && S.ready) scheduleReport(); }, { passive: true });
  window.addEventListener('resize', function () {
    if (!S.chapter || !S.ready) return;
    var a = S.lastAnchor;
    clearTimeout(S.relayoutTimer);
    S.relayoutTimer = setTimeout(function () { relayout(a || undefined); }, 80);
  });

  // ------------------------------------------------------------------ public API

  window.Reader = {
    setSettings: function (s) {
      var prevS = S.settings;
      var a = S.ready ? captureAnchor() : null;
      S.settings = Object.assign({}, S.settings, s || {});
      if (prevS.zoom !== S.settings.zoom) applyZoom();
      var hzChanged = prevS.forceHorizontal !== S.settings.forceHorizontal;
      if (hzChanged && S.chapter) {
        // Reload so vertical presentation forms and layout are rebuilt from scratch.
        loadChapter({ index: S.chapter.index, url: S.chapter.url, mime: S.chapter.mime, count: S.chapter.count,
          position: a ? { type: 'anchor', anchor: anchorString(a) } : { type: 'start' } });
        return;
      }
      if (prevS.paged !== S.settings.paged) {
        S.page = 0;
        setOffset(0, false);
        content.style.transform = '';
      }
      if (S.chapter) relayout(a);
    },
    loadChapter: function (msg) { return loadChapter(msg); },
    next: next,
    prev: prev,
    goToFraction: goToFraction,
    goToAnchor: function (anchor, fraction) { positionAt({ type: 'anchor', anchor: anchor, fraction: fraction }); },
    goToFragment: function (id) { positionAt({ type: 'fragment', id: id }); },
    requestBlocks: requestBlocks,
    setTranslations: setTranslations,
    clearTranslations: clearTranslations,
    search: function (q, occ) { return highlightSearch(q, occ || 0); },
    clearSearch: clearSearch,
    getSelectionText: function () { var s = window.getSelection(); return s ? s.toString() : ''; },
    // Exposed for tests.
    _state: function () { return { page: S.page, pages: S.pages, offset: S.offset, W: S.W, H: S.H, blocks: S.blocks.length, anchor: anchorString(S.lastAnchor) }; },
    _captureAnchor: function () { return anchorString(captureAnchor()); },
    _blockText: function () { return S.blockText.slice(); }
  };

  post('ready');
})();
