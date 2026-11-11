// Render the NEIS MVP source pages with a real browser (Playwright/Chromium)
// and save the rendered HTML5 as static test data for the MVP chain.
// Usage: node fetch-rendered.js <outdir>
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const SOURCES = [
  {
    id: 'ADDR-001',
    url: 'https://ec.europa.eu/digital-building-blocks/sites/spaces/EUDIGITALIDENTITYWALLET/pages/712507533/News',
    file: 'addr-001-news.html',
  },
  {
    id: 'ADDR-002',
    url: 'https://ec.europa.eu/digital-building-blocks/sites/spaces/EUDIGITALIDENTITYWALLET/pages/694497147/Events',
    file: 'addr-002-events.html',
  },
  {
    id: 'ADDR-003',
    url: 'https://ec.europa.eu/info/funding-tenders/opportunities/portal/screen/support/news?sortBy=publicationDate&order=DESC&pageNumber=1&pageSize=10&isExactMatch=false',
    file: 'addr-003-news-spa-rendered.html',
  },
  {
    id: 'ADDR-004',
    url: 'https://ec.europa.eu/info/funding-tenders/opportunities/portal/screen/support/events?isExactMatch=false&order=DESC&pageNumber=1&pageSize=50&sortBy=status',
    file: 'addr-004-events-spa-rendered.html',
  },
];

(async () => {
  const outdir = process.argv[2] || '.';
  fs.mkdirSync(outdir, { recursive: true });
  const browser = await chromium.launch({ headless: true });

  for (const src of SOURCES) {
    const page = await browser.newPage({
      userAgent: 'Mozilla/5.0 (X11; Linux x86_64) NEIS-MVP-fetch',
      viewport: { width: 1280, height: 900 },
    });
    try {
      await page.goto(src.url, { waitUntil: 'networkidle', timeout: 90000 });
      // Let late XHR-driven rendering settle (portal lists, Confluence macros).
      await page.waitForTimeout(3000);
      const html = await page.content();
      const out = path.join(outdir, src.file);
      fs.writeFileSync(out, html, 'utf8');
      console.log(`${src.id}: ${html.length} bytes -> ${out}`);
    } catch (e) {
      console.error(`${src.id}: FAILED - ${e.message.split('\n')[0]}`);
      process.exitCode = 1;
    } finally {
      await page.close();
    }
  }
  await browser.close();
})();
