/*
 * Reproducible README-hero capture (the deterministic-work-gets-a-script rule
 * applied to screenshots). Run from any directory that has @playwright/test
 * installed (the upstream e2e module does):
 *
 *   node docs/img/capture-explorer.cjs [path-to-playground-dir]
 *
 * Captures the sim's default seeded world (deterministic) in dark theme at a
 * fixed viewport, writing write-path-explorer.png beside this script.
 */
const path = require('path');
const { chromium } = require(path.join(process.cwd(), 'node_modules', '@playwright/test'));

(async () => {
  const playground = path.resolve(process.argv[2] ?? path.join(__dirname, '..', '..', 'prolly-web-playground'));
  const browser = await chromium.launch();
  const page = await browser.newPage({
    viewport: { width: 1600, height: 980 },
    deviceScaleFactor: 2,
    colorScheme: 'dark',
    reducedMotion: 'reduce',
  });
  await page.goto('file://' + path.join(playground, 'write-path-explorer.html'));
  await page.waitForSelector('#narration >> text=⋄'); // the seeded world narrated by hash
  // one insert: the hero must SHOW the claim — before/after trees, the amber
  // rewritten spine, everything else reached by reference (deterministic: the
  // first enabled gap of the seeded world always inserts the same key)
  await page.locator('#ribbon .gap:enabled').first().click();
  await page.waitForSelector('#narration >> text=wrote');
  // compose the frame: the write-path stepper and the operation journal grew in
  // between the panes and the ribbon since the original hero — close the stepper
  // and empty the journal so the hero stays the core claim (trees + spine + ribbon)
  await page.locator('#rclose').click();
  await page.locator('#rclear').click();
  // the ribbon click scrolled the page (the narration is sticky) — frame from the top
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(600); // fonts + svg + pane fit settle
  // frame the stage: narration + stats through the panes + leaf ribbon
  const top = await page.locator('#narration').boundingBox();
  const bottom = await page.locator('#ribbonwrap').boundingBox();
  await page.screenshot({
    path: path.join(__dirname, 'write-path-explorer.png'),
    fullPage: true, // the frame is taller than the viewport
    clip: { x: top.x - 8, y: top.y - 2,
            width: Math.min(1600 - (top.x - 8), top.width + 16),
            height: bottom.y + bottom.height - top.y + 14 },
  });
  await browser.close();
  console.log('captured docs/img/write-path-explorer.png');
})();
