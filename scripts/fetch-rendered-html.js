// Render the NEIS MVP source pages with a real browser (Playwright/Chromium)
// and save the rendered HTML5 as static test data for the MVP chain.
// Usage: node fetch-rendered-html.js <outdir> [id-filter ...]
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
  {
    // HADEA (HaDEA) events list, page 1 of 2 (Drupal OpenEuropa list page;
    // 10 events per page, facet filter form, pager links ?page=N).
    id: 'ADDR-005',
    url: 'https://hadea.ec.europa.eu/events_en',
    file: 'addr-005-events-rendered.html',
  },
  {
    // HADEA events list, page 2 of 2 (?page=1, last 3 events).
    id: 'ADDR-005-P2',
    url: 'https://hadea.ec.europa.eu/events_en?page=1',
    file: 'addr-005-events-page2-rendered.html',
  },
  {
    // HADEA news list, page 1 of 9 (100 news per page, same facet form).
    id: 'ADDR-006',
    url: 'https://hadea.ec.europa.eu/news_en',
    file: 'addr-006-news-rendered.html',
  },
  {
    // EMA (European Medicines Agency) news list, page 1 of ~390 (Drupal,
    // 12 news per page, keyword filter form, pager links ?page=N).
    id: 'ADDR-007',
    url: 'https://www.ema.europa.eu/en/news',
    file: 'addr-007-news-rendered.html',
  },
  ...[1, 2, 3, 4].map((p) => ({
    id: `ADDR-007-P${p + 1}`,
    url: `https://www.ema.europa.eu/en/news?page=${p}`,
    file: `addr-007-news-page${p + 1}-rendered.html`,
  })),
  {
    // EMA upcoming events list, page 1 of 9 (12 events per page,
    // topic/category/responsible-body filter selects).
    id: 'ADDR-008',
    url: 'https://www.ema.europa.eu/en/events/upcoming-events',
    file: 'addr-008-events-rendered.html',
  },
  ...[1, 2, 3, 4].map((p) => ({
    id: `ADDR-008-P${p + 1}`,
    url: `https://www.ema.europa.eu/en/events/upcoming-events?page=${p}`,
    file: `addr-008-events-page${p + 1}-rendered.html`,
  })),
];

(async () => {
  const outdir = process.argv[2] || '.';
  const filter = process.argv.slice(3);
  fs.mkdirSync(outdir, { recursive: true });
  const browser = await chromium.launch({ headless: true });

  for (const src of SOURCES) {
    if (filter.length && !filter.includes(src.id)) continue;
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
