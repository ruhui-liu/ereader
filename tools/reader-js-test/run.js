/*
 * Headless-Chromium tests for the reader engine (app/src/main/assets/reader/reader.js).
 * Android WebView is Chromium, so this exercises the same layout engine.
 *
 *   npm i -g playwright && npx playwright install chromium   (once)
 *   node tools/reader-js-test/run.js [screenshot-dir]
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const ROOT = path.resolve(__dirname, '../..');
const MOUNTS = { '/assets/': path.join(ROOT, 'app/src/main/assets'), '/book/': path.join(__dirname, 'fixtures') };
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.xhtml': 'application/xhtml+xml' };
const SHOTS = process.argv[2] || null;
const PORT = 8765;

function serve() {
  return new Promise(res => {
    const server = http.createServer((req, resp) => {
      const url = decodeURIComponent(req.url.split('?')[0]);
      const mount = Object.keys(MOUNTS).find(m => url.startsWith(m));
      const file = mount && path.join(MOUNTS[mount], url.slice(mount.length));
      if (!file || !fs.existsSync(file)) { resp.writeHead(404); resp.end(); return; }
      resp.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' });
      fs.createReadStream(file).pipe(resp);
    }).listen(PORT, () => res(server));
  });
}

(async () => {
  const server = await serve();
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 400, height: 760 }, deviceScaleFactor: 1, hasTouch: true });
  page.on('console', m => console.log('[console]', m.text()));
  page.on('pageerror', e => console.log('[pageerror]', e.message));
  await page.goto('http://localhost:8765/assets/reader/reader.html');
  const msgs = () => page.evaluate(() => (window.__ydMessages || []).splice(0));
  const base = { fontSize: 18, lineHeight: 1.7, paraSpacing: 0.6, marginH: 20, marginV: 28, fontFamily: 'serif',
    bg: '#f5ecd7', fg: '#4a3b2a', trColor: '#8a5a2b', linkColor: '#2f6db3', textAlign: 'justify', indent: '2em',
    override: true, showRuby: true, paged: true, vertical: false, forceHorizontal: false, rtl: false, animate: false,
    trMode: 1, trScale: 0.92, bookLang: 'en', trLang: 'zh-CN' };
  let ok = true;
  const check = (cond, label) => { console.log((cond ? 'PASS ' : 'FAIL ') + label); if (!cond) ok = false; };

  // ---------- English, paged horizontal
  await page.evaluate(s => Reader.setSettings(s), base);
  await page.evaluate(() => Reader.loadChapter({ index: 0, url: '/book/OEBPS/Text/en.xhtml', mime: 'application/xhtml+xml', count: 3, position: { type: 'start' } }));
  await page.waitForTimeout(400);
  let st = await page.evaluate(() => Reader._state());
  console.log('en state', JSON.stringify(st));
  check(st.pages > 3, 'english has several pages');
  let m = await msgs();
  check(m.some(x => x.type === 'chapterLoaded'), 'chapterLoaded posted');
  const pos0 = m.filter(x => x.type === 'position').pop();
  check(pos0 && pos0.anchor === '0:0', 'anchor at start is 0:0 (' + (pos0 && pos0.anchor) + ')');
  await SHOTS && page.screenshot({ path: SHOTS + '/en_p0.png' });
  // Real content width check: does any text overflow beyond column? check that page 1 starts where page 0 ended.
  await page.evaluate(() => Reader.next());
  await page.waitForTimeout(350);
  st = await page.evaluate(() => Reader._state());
  check(st.page === 1, 'next() goes to page 1');
  await SHOTS && page.screenshot({ path: SHOTS + '/en_p1.png' });
  m = await msgs();
  const p1 = m.filter(x => x.type === 'position').pop();
  console.log('page1 anchor', p1 && p1.anchor, p1 && p1.excerpt);
  // Go to page 3, capture anchor, change font size, anchor should be visible on new page.
  await page.evaluate(() => { Reader.next(); Reader.next(); });
  await page.waitForTimeout(350);
  m = await msgs();
  const before = m.filter(x => x.type === 'position').pop();
  await page.evaluate(() => Reader.setSettings({ fontSize: 24 }));
  await page.waitForTimeout(350);
  m = await msgs();
  const after = m.filter(x => x.type === 'position').pop();
  st = await page.evaluate(() => Reader._state());
  console.log('before', before.anchor, before.page, '/', before.pages, ' after', after.anchor, after.page, '/', after.pages);
  const [bb, bo] = before.anchor.split(':').map(Number), [ab, ao] = after.anchor.split(':').map(Number);
  check(ab < bb || (ab === bb && ao <= bo), 'after font change, page starts at or before old anchor');
  check(after.pages > before.pages, 'bigger font -> more pages');
  // Translations: insert translations for all blocks; anchor must stay on page.
  await page.evaluate(() => Reader.requestBlocks());
  m = await msgs();
  const blocks = m.find(x => x.type === 'blocks');
  check(blocks && blocks.items.length > 30, 'blocks posted: ' + (blocks && blocks.items.length));
  console.log('first blocks', JSON.stringify(blocks.items.slice(0, 3)));
  check(blocks.items[1].t.indexOf('italic word') >= 0, 'inline whitespace preserved in block text');
  const anchorBefore = await page.evaluate(() => Reader._captureAnchor());
  await page.evaluate((items) => Reader.setTranslations(0, items.map(it => ({ i: it.i, t: '【译】这是第' + it.i + '段的中文翻译，用于测试双语对照排版效果。' }))), blocks.items);
  await page.waitForTimeout(350);
  const anchorAfter = await page.evaluate(() => Reader._captureAnchor());
  st = await page.evaluate(() => Reader._state());
  console.log('translation anchor', anchorBefore, '->', anchorAfter, 'pages', st.pages);
  const [x1, y1] = anchorBefore.split(':').map(Number), [x2, y2] = anchorAfter.split(':').map(Number);
  check(x2 < x1 || (x2 === x1 && y2 <= y1), 'translation insertion keeps reading position');
  await page.evaluate(() => Reader.setSettings({ fontSize: 18 }));
  await page.evaluate(() => Reader.goToFraction(0));
  await page.waitForTimeout(300);
  await SHOTS && page.screenshot({ path: SHOTS + '/en_bilingual.png' });
  // translation only mode
  await page.evaluate(() => Reader.setSettings({ trMode: 2 }));
  await page.waitForTimeout(300);
  await SHOTS && page.screenshot({ path: SHOTS + '/en_tronly.png' });
  const visibleOrig = await page.evaluate(() => getComputedStyle(document.querySelector('[data-yd-b="2"]')).display);
  check(visibleOrig === 'none', 'translation-only hides originals');
  await page.evaluate(() => Reader.setSettings({ trMode: 0 }));
  // search
  const found = await page.evaluate(() => Reader.search('candle', 3));
  check(found, 'search finds occurrence');
  st = await page.evaluate(() => Reader._state());
  console.log('search page', st.page);
  await SHOTS && page.screenshot({ path: SHOTS + '/en_search.png' });
  // footnote click
  await page.evaluate(() => Reader.goToFraction(0));
  await page.waitForTimeout(200);
  const fnBox = await page.evaluate(() => { const r = document.querySelector('a[href*="fn1"]').getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; });
  await page.mouse.click(fnBox.x, fnBox.y);
  await page.waitForTimeout(200);
  const noteShown = await page.evaluate(() => !document.getElementById('yd-note').hidden && document.getElementById('yd-note').textContent);
  check(!!noteShown && noteShown.indexOf('footnote text') >= 0, 'footnote popup shows note');
  await SHOTS && page.screenshot({ path: SHOTS + '/en_footnote.png' });
  await page.mouse.click(200, 100);
  // tap zones
  await msgs();
  await page.mouse.click(390, 400);
  await page.waitForTimeout(300);
  st = await page.evaluate(() => Reader._state());
  check(st.page === 1, 'tap right -> next page');
  await page.mouse.click(200, 400);
  m = await msgs();
  check(m.some(x => x.type === 'menu'), 'tap center -> menu');
  // last page -> boundary
  await page.evaluate(() => Reader.goToFraction(1));
  await msgs();
  await page.evaluate(() => Reader.next());
  m = await msgs();
  check(m.some(x => x.type === 'boundary' && x.dir === 'next'), 'next on last page -> boundary');
  // last page should contain text (no blank trailing page)
  await SHOTS && page.screenshot({ path: SHOTS + '/en_last.png' });
  const lastHasText = await page.evaluate(() => {
    const W = innerWidth; let found = false;
    const w = document.createTreeWalker(document.getElementById('yd-content'), NodeFilter.SHOW_TEXT);
    let n; while ((n = w.nextNode())) { if (!/\S/.test(n.data) || n.parentElement.closest('.yd-ui')) continue; const r = document.createRange(); r.selectNodeContents(n); for (const rc of r.getClientRects()) if (rc.left >= 0 && rc.left < W && rc.width > 0) found = true; }
    return found;
  });
  check(lastHasText, 'last page is not blank');

  // ---------- Japanese vertical, converted to horizontal
  await page.evaluate(s => Reader.setSettings(s), Object.assign({}, base, { bookLang: 'ja', forceHorizontal: true, vertical: false, trMode: 1 }));
  await page.evaluate(() => Reader.loadChapter({ index: 1, url: '/book/OEBPS/Text/jp.xhtml', mime: 'application/xhtml+xml', count: 3, position: { type: 'start' } }));
  await page.waitForTimeout(500);
  st = await page.evaluate(() => Reader._state());
  console.log('jp hz state', JSON.stringify(st));
  const wm = await page.evaluate(() => getComputedStyle(document.querySelector('#yd-content p')).writingMode);
  check(wm === 'horizontal-tb', 'vertical book converted to horizontal-tb (' + wm + ')');
  const conv = await page.evaluate(() => document.querySelector('#yd-content p').textContent);
  check(conv.indexOf('︒') < 0 && conv.indexOf('。') >= 0 && conv.indexOf('？') >= 0, 'vertical presentation forms converted');
  await msgs();
  await page.evaluate(() => Reader.requestBlocks());
  m = await msgs();
  const jb = m.find(x => x.type === 'blocks');
  console.log('jp block[1]', JSON.stringify(jb.items[1]).slice(0, 80));
  check(jb.items[1].t.indexOf('わがはい') < 0, 'ruby readings excluded from translation text');
  await page.evaluate((items) => Reader.setTranslations(1, items.slice(0, 6).map(it => ({ i: it.i, t: '我是猫。名字还没有。完全不知道自己是在哪里出生的。' }))), jb.items);
  await page.waitForTimeout(300);
  await SHOTS && page.screenshot({ path: SHOTS + '/jp_horizontal.png' });

  // ---------- Japanese vertical, original layout (paged vertical-rl)
  await page.evaluate(s => Reader.setSettings(s), Object.assign({}, base, { bookLang: 'ja', forceHorizontal: false, vertical: true, rtl: true, trMode: 0 }));
  await page.waitForTimeout(600);
  st = await page.evaluate(() => Reader._state());
  console.log('jp vertical state', JSON.stringify(st));
  check(st.pages > 2, 'vertical paged has pages');
  await SHOTS && page.screenshot({ path: SHOTS + '/jp_vertical_p0.png' });
  await page.evaluate(() => Reader.next());
  await page.waitForTimeout(300);
  await SHOTS && page.screenshot({ path: SHOTS + '/jp_vertical_p1.png' });
  st = await page.evaluate(() => Reader._state());
  check(st.page === 1, 'vertical next page');
  await page.mouse.click(10, 400); // tap left = next in rtl
  await page.waitForTimeout(300);
  st = await page.evaluate(() => Reader._state());
  check(st.page === 2, 'rtl: tap left -> next');

  // ---------- scroll mode
  await page.evaluate(s => Reader.setSettings(s), Object.assign({}, base, { paged: false, trMode: 0 }));
  await page.evaluate(() => Reader.loadChapter({ index: 0, url: '/book/OEBPS/Text/en.xhtml', mime: 'application/xhtml+xml', count: 3, position: { type: 'fraction', value: 0.5 } }));
  await page.waitForTimeout(500);
  await msgs();
  const sp = await page.evaluate(() => document.getElementById('yd-viewport').scrollTop);
  check(sp > 1000, 'scroll mode positioned at fraction 0.5 (scrollTop=' + sp + ')');
  await SHOTS && page.screenshot({ path: SHOTS + '/en_scroll.png' });

  console.log(ok ? 'ALL PASSED' : 'SOME FAILED');
  await browser.close();
  server.close();
  process.exit(ok ? 0 : 1);
})();
