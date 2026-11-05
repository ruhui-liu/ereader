/*
 * Reader-engine tests for PDF chapters (original page images and re-flowed text), in headless Chromium.
 *   node tools/reader-js-test/pdf.js [screenshot-dir]
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const ROOT = path.resolve(__dirname, '../..');
const MOUNTS = { '/assets/': path.join(ROOT, 'app/src/main/assets'), '/book/': path.join(__dirname, 'fixtures') };
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.jpg': 'image/jpeg' };
const SHOTS = process.argv[2] || null;
const PORT = 8766;

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
  const page = await browser.newPage({ viewport: { width: 400, height: 760 }, hasTouch: true });
  page.on('pageerror', e => console.log('[pageerror]', e.message));
  await page.goto(`http://localhost:${PORT}/assets/reader/reader.html`);
  const msgs = () => page.evaluate(() => (window.__ydMessages || []).splice(0));
  const lastPos = async () => (await msgs()).filter(m => m.type === 'position').pop();
  let ok = true;
  const check = (c, label) => { console.log((c ? 'PASS ' : 'FAIL ') + label); if (!c) ok = false; };
  const base = { animate: false, paged: true, marginH: 6, marginV: 22, bg: '#f5ecd7', fg: '#4a3b2a', zoom: true, dark: false, trMode: 1, forceHorizontal: true };

  // ---------- original page images
  await page.evaluate(s => Reader.setSettings(s), base);
  await page.evaluate(() => Reader.loadChapter({ index: 0, url: '/book/PDF/0.html', mime: 'text/html', count: 2, position: { type: 'fragment', id: 'yd-page-3' } }));
  await page.waitForTimeout(600);
  let st = await page.evaluate(() => Reader._state());
  console.log('orig state', JSON.stringify(st));
  check(st.pages === 10, 'one screen page per PDF page (' + st.pages + ')');
  check(st.page === 3, 'fragment yd-page-3 opens page 4');
  let pos = await lastPos();
  check(pos && pos.pdfPage === 3 && /^p3:/.test(pos.anchor), 'reports pdfPage 3 and a page anchor (' + (pos && pos.anchor) + ')');
  const meta = await page.evaluate(() => document.querySelector('meta[name=viewport]').content);
  check(/user-scalable=yes/.test(meta), 'pinch zoom enabled for page images');
  const hidden = await page.evaluate(() => getComputedStyle(document.querySelector('.yd-pdf-text p')).display);
  check(hidden === 'none', 'page text is hidden in original mode');
  SHOTS && await page.screenshot({ path: SHOTS + '/pdf_orig.png' });
  // translations go below each page; position stays on the same PDF page
  await page.evaluate(() => Reader.requestBlocks());
  const blocks = (await msgs()).find(m => m.type === 'blocks');
  check(blocks && blocks.items.length === 30 && blocks.center === 9, 'hidden page text is offered for translation, centred on page 4 (' + (blocks && blocks.center) + ')');
  await page.evaluate(items => Reader.setTranslations(0, items.map(it => ({ i: it.i, t: '【译】第' + it.i + '段：雨停了，街道很安静；远处有条狗叫了两声，又安静下来。' }))), blocks.items);
  await page.waitForTimeout(400);
  pos = await lastPos();
  st = await page.evaluate(() => Reader._state());
  console.log('after translation', JSON.stringify(st), pos && pos.anchor);
  check(st.pages > 10, 'translations add screen pages');
  check(pos && pos.pdfPage === 3, 'still on PDF page 4 after inserting translations');
  const top = await page.evaluate(() => { const r = document.getElementById('yd-page-3').getClientRects()[0]; return [Math.round(r.left), Math.round(r.top)]; });
  check(top[0] >= 0 && top[0] < 20, 'page 4 image is at the top-left of the screen (' + top + ')');
  SHOTS && await page.screenshot({ path: SHOTS + '/pdf_orig_bilingual.png' });
  await page.evaluate(() => Reader.next());
  await page.waitForTimeout(350);
  SHOTS && await page.screenshot({ path: SHOTS + '/pdf_orig_bilingual_next.png' });
  pos = await lastPos();
  check(pos && pos.pdfPage === 3 && pos.anchor !== 'p3:0.0000', 'next screen shows page 4 translations (' + (pos && pos.anchor) + ')');
  // restore by page anchor after a font change
  const before = pos.anchor;
  await page.evaluate(() => Reader.setSettings({ fontSize: 24 }));
  await page.waitForTimeout(350);
  pos = await lastPos();
  check(pos && pos.pdfPage === 3, 'page anchor survives relayout (' + before + ' -> ' + (pos && pos.anchor) + ')');
  // dark theme inverts page images
  await page.evaluate(() => Reader.setSettings({ dark: true, bg: '#1e1f22', fg: '#c9c9c9' }));
  const filter = await page.evaluate(() => getComputedStyle(document.querySelector('.yd-pdf-page img')).filter);
  check(/invert/.test(filter), 'dark theme inverts page images');
  if (SHOTS) {
    await page.evaluate(() => Reader.goToFraction(0));
    await page.waitForTimeout(600);
    await page.screenshot({ path: SHOTS + '/pdf_orig_dark.png' });
  }
  // scroll mode
  await page.evaluate(() => Reader.setSettings({ paged: false, dark: false, bg: '#f5ecd7', fg: '#4a3b2a', trMode: 0 }));
  await page.evaluate(() => Reader.goToAnchor('p6:0', 0));
  await page.waitForTimeout(400);
  pos = await lastPos();
  check(pos && pos.pdfPage === 6, 'scroll mode: page anchor p6 restores page 7 (' + (pos && pos.pdfPage) + ')');

  // ---------- re-flowed text
  await page.evaluate(s => Reader.setSettings(s), Object.assign({}, base, { zoom: false, marginH: 22, marginV: 30, trMode: 0, fontSize: 19 }));
  await page.evaluate(() => Reader.loadChapter({ index: 0, url: '/book/PDF/r0.html', mime: 'text/html', count: 2, position: { type: 'fragment', id: 'yd-page-6' } }));
  await page.waitForTimeout(600);
  pos = await lastPos();
  check(pos && pos.pdfPage === 6, 'reflow: fragment yd-page-6 reports pdfPage 6 (' + (pos && pos.pdfPage) + ')');
  check(pos && /^\d+:\d+$/.test(pos.anchor), 'reflow uses text anchors (' + (pos && pos.anchor) + ')');
  const meta2 = await page.evaluate(() => document.querySelector('meta[name=viewport]').content);
  check(/user-scalable=no/.test(meta2), 'zoom disabled for re-flowed text');
  await page.evaluate(() => Reader.goToFragment('yd-page-4'));
  await page.waitForTimeout(300);
  SHOTS && await page.screenshot({ path: SHOTS + '/pdf_reflow_figure.png' });
  pos = await lastPos();
  check(pos && (pos.pdfPage === 4 || pos.pdfPage === 3), 'image-only page shown in reflow (' + (pos && pos.pdfPage) + ')');
  await page.evaluate(() => Reader.goToFraction(0));
  await page.waitForTimeout(300);
  SHOTS && await page.screenshot({ path: SHOTS + '/pdf_reflow.png' });

  console.log(ok ? 'ALL PASSED' : 'SOME FAILED');
  await browser.close();
  server.close();
  process.exit(ok ? 0 : 1);
})();
