/*
 * Reproducible bench + compare-stores captures (the deterministic-work-gets-a-script
 * rule applied to screenshots). Unlike the sim hero, these run REAL mode, so they need
 * the playground service up with data in its tree:
 *
 *   java -jar prolly-playground-service/target/prolly-playground-service-*.jar \
 *     --playground.store=file --playground.store-dir=$HOME/.local/share/prolly-playground
 *   node docs/img/capture-bench.cjs [playground-dir] [service-base]
 *
 * The FRAMING is deterministic; the numbers are honestly whatever the machine measures
 * (the UI's own label says so). Writes are restorative by design: the write bench
 * deletes its keys and asserts the root hash returns byte-identical, and the compare
 * arms are ephemeral — the live tree passes through untouched.
 * Writes bench.png + compare-stores.png + hex-viewer.png + pane-cap.png +
 * root-chain.png + mode-switch.png beside this script.
 */
const path = require('path');
const { chromium } = require(path.join(process.cwd(), 'node_modules', '@playwright/test'));

(async () => {
  const playground = path.resolve(process.argv[2] ?? path.join(__dirname, '..', '..', 'prolly-web-playground'));
  const base = process.argv[3] ?? 'http://localhost:8080';
  const browser = await chromium.launch();
  const page = await browser.newPage({
    viewport: { width: 1600, height: 2000 },
    deviceScaleFactor: 2,
    colorScheme: 'dark',
    reducedMotion: 'reduce',
  });
  await page.goto('file://' + path.join(playground, 'write-path-explorer.html') + '?real=' + encodeURIComponent(base));
  await page.waitForSelector('#narration >> text=⋄');
  await page.click('#mode-real');
  await page.waitForSelector('#narration >> text=REAL ENGINE');

  // mode-switch.png: the header with the store switch — 'real engine' lit. The whole
  // header row gives the context (this is page chrome, not a buried setting).
  {
    const scroll = await page.evaluate(() => ({ x: scrollX, y: scrollY }));
    const hdr = await page.locator('header').boundingBox();
    await page.screenshot({
      path: path.join(__dirname, 'mode-switch.png'),
      fullPage: true,
      clip: { x: hdr.x + scroll.x, y: hdr.y + scroll.y, width: hdr.width, height: hdr.height },
    });
    console.log('captured docs/img/mode-switch.png');
  }

  const shoot = async (name) => {
    // buttons re-enable only after the post-op refetch settles — never shoot mid-flight
    await page.waitForFunction(() => !document.getElementById('benchread').disabled);
    // the results table is wider than the rail (the page scrolls it; overflow-x:auto) —
    // for the shot, widen the whole SECTION over an opaque background so the full
    // table renders and no neighboring rail content bleeds into the clip
    await page.locator('#realsection').evaluate((s) => {
      const b = document.getElementById('benchout');
      s.dataset.w = s.style.width;
      s.style.width = (b.scrollWidth + (s.offsetWidth - b.offsetWidth)) + 'px';
      s.style.position = 'relative'; s.style.zIndex = '10';
      s.style.background = 'var(--ink0)';
    });
    // boundingBox() is VIEWPORT-relative; a fullPage clip is PAGE-relative — add scroll
    const scroll = await page.evaluate(() => ({ x: scrollX, y: scrollY }));
    const sec = await page.locator('#realsection').boundingBox();
    const top = await page.locator('#realbenchrow').boundingBox();
    const out = await page.locator('#benchout').boundingBox();
    await page.screenshot({
      path: path.join(__dirname, name),
      fullPage: true,
      // x/width from the widened section itself — nothing outside its background
      clip: { x: sec.x + scroll.x, y: top.y + scroll.y - 6,
              width: sec.width,
              height: out.y + out.height - top.y + 12 },
    });
    await page.locator('#realsection').evaluate((s) => {
      s.style.width = s.dataset.w; s.style.position = ''; s.style.zIndex = '';
      s.style.background = '';
    });
    console.log('captured docs/img/' + name);
  };

  // bench.png: a read bench then a write bench — the output shows the percentile strip,
  // the root-restored celebration (history-independence, live), and the session history
  await page.click('#benchread');
  await page.waitForSelector('#benchout >> text=ops/s');
  await page.click('#benchwrite');
  await page.waitForSelector('#benchout >> text=root restored', { timeout: 60_000 });
  await page.waitForTimeout(300);
  await shoot('bench.png');

  // compare-stores.png: the three byte-equal arms — throughput + layered latency panels
  // (solid p50 / mid p95 / pale p99 / max ticks) + the numbers table + identity proofs
  await page.click('#benchcmp');
  await page.waitForSelector('#benchout >> text=identical ✓', { timeout: 120_000 });
  await page.waitForTimeout(300);
  await shoot('compare-stores.png');

  // pane-cap.png: the top view of the 1M tree — the root plus its 32 children
  // collapsed to dashed zoom-stubs, with the breadcrumb narrating the truncation
  // (the pane is still in this state after the hex section's fresh load)
  {
    // frame tight: hide the (same-root) BEFORE pane so the crumb sits on the AFTER
    // pane, and crop the pane's vertical emptiness to the actual node content
    await page.locator('.pane-before').evaluate((n) => { n.style.display = 'none'; });
    await page.waitForTimeout(200);
    const scroll = await page.evaluate(() => ({ x: scrollX, y: scrollY }));
    const crumb = await page.locator('#realcrumb').boundingBox();
    const pane = await page.locator('.pane-after').boundingBox();
    const nodesBottom = await page.evaluate(() => {
      let b = 0;
      document.querySelectorAll('#aftersvg g.nd').forEach((g) => {
        b = Math.max(b, g.getBoundingClientRect().bottom);
      });
      return b;
    });
    await page.screenshot({
      path: path.join(__dirname, 'pane-cap.png'),
      fullPage: true,
      clip: { x: pane.x + scroll.x, y: crumb.y + scroll.y - 4,
              width: pane.width,
              height: nodesBottom + scroll.y - crumb.y + 18 },
    });
    await page.locator('.pane-before').evaluate((n) => { n.style.display = ''; });
    console.log('captured docs/img/pane-cap.png');
  }


  // hex-viewer.png: the field-annotated hex grid — click the AFTER pane's root node,
  // open its stored bytes, and hover a key byte so the cross-highlight + the decoded
  // tooltip (both real DOM, not native title popups) render into the shot. Framed from
  // the details summary through the tooltip line — the ~100-row field list below is
  // cropped (the grid is the star; the fields are its long-form index).
  // fresh load first: the section-widening in shoot() perturbs the pane layout, and
  // the hex shot should not depend on bench state anyway (mode-real persists)
  await page.reload();
  await page.waitForSelector('#narration >> text=REAL ENGINE');
  await page.waitForSelector('#aftersvg g.nd');
  // at 1M keys the pane is mostly zoom-stubs — click a REAL node (the root), not a stub
  await page.locator('#aftersvg g.nd:not(.stub)').first().click();
  await page.waitForSelector('#realinspect .real-banner');
  await page.locator('#realinspect details.ibytes summary').click();
  await page.waitForSelector('#realinspect .hx-grid .hx-b');
  // the dump is a scroll-capped box in the app — uncap it for the shot so the whole
  // grid + legend + tooltip line are in frame (1285 B ≈ 40 rows)
  await page.locator('#realinspect .hexdump').evaluate((n) => {
    n.style.maxHeight = 'none'; n.style.overflow = 'visible';
    const g = n.querySelector('.hx-grid');
    g.style.maxHeight = 'none'; g.style.overflow = 'visible';
  });
  await page.locator('#realinspect .hx-grid .hx-b.hx-key').nth(10).hover();
  await page.waitForTimeout(200);
  {
    // same page-relative correction as shoot()
    const scroll = await page.evaluate(() => ({ x: scrollX, y: scrollY }));
    const det = await page.locator('#realinspect details.ibytes').boundingBox();
    const tip = await page.locator('#realinspect .hx-tip').boundingBox();
    await page.screenshot({
      path: path.join(__dirname, 'hex-viewer.png'),
      fullPage: true,
      clip: { x: det.x + scroll.x - 6, y: det.y + scroll.y - 6, width: det.width + 12,
              height: tip.y + tip.height - det.y + 12 },
    });
    console.log('captured docs/img/hex-viewer.png');
  }

  // root-chain.png: mint session history RESTORATIVELY — insert one key far outside
  // every generator's range, refresh (logs the new root), delete it, refresh (the
  // root returns byte-identical, logged again): three chips, head marked, the middle
  // one a superseded root still fully renderable from the store
  const K = 999_999_999_999_999; // > bulk max (~9e12), < bench floor (1e15)
  await page.evaluate(async (k) => {
    await fetch('http://localhost:8080/api/tree/keys', { method: 'POST',
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ keys: [k] }) });
  }, K);
  await page.click('#realrefresh');
  await page.waitForFunction(() => document.querySelectorAll('#rootlog .rchip').length >= 2);
  await page.evaluate(async (k) => {
    await fetch('http://localhost:8080/api/tree/keys', { method: 'DELETE',
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ keys: [k] }) });
  }, K);
  await page.click('#realrefresh');
  await page.waitForFunction(() => document.querySelectorAll('#rootlog .rchip').length >= 3);
  await page.waitForTimeout(300);
  {
    // the strip is page-wide and mostly empty at 3 chips — narrow it for the shot so
    // the label reflows and the image has README proportions
    await page.locator('#realstriprow').evaluate((n) => { n.style.width = '980px'; });
    await page.waitForTimeout(200);
    const scroll = await page.evaluate(() => ({ x: scrollX, y: scrollY }));
    const strip = await page.locator('#realstriprow').boundingBox();
    await page.screenshot({
      path: path.join(__dirname, 'root-chain.png'),
      fullPage: true,
      clip: { x: strip.x + scroll.x - 4, y: strip.y + scroll.y - 4,
              width: strip.width + 8, height: strip.height + 8 },
    });
    await page.locator('#realstriprow').evaluate((n) => { n.style.width = ''; });
    console.log('captured docs/img/root-chain.png');
  }

  await browser.close();
})();
