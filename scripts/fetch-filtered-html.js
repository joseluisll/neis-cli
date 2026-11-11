// Capture the FILTERED response of the MVP source pages with a real
// browser (Playwright/Chromium): load the listing WITH one filter
// criterion of the site's OWN filter mechanism expressed the way the
// site itself expresses it (URL facet/filters parameters — discovered
// by operating the site's controls like a user), wait for the filtered
// listing to render, and save the DOM as a static fixture for the MVP
// chain's FILTER application step (US-06.6 slice).
//
// Usage: NODE_PATH=<npx-cache>/node_modules node fetch-filtered-html.js <outdir> [id ...]
//
// The criterion applied per source is recorded in the SOURCES entries
// below and in <outdir>/README.md — it is the criterion each captured
// `addr-*-filtered-rendered.html` fixture embodies. The criteria are the
// ones the MVP chain's apply prompt actually chose on 2026-11-11, so the
// captured responses correspond to what the chain asks the connector to
// apply (the fixture stands in for the site's filtered response).
//
// URL forms (all discovered by operating the real controls in a browser:
// the Funding portal writes `programmes=...` / `timeframe=past`, HaDEA
// writes `f[0]=<facet>:<value>`, EMA writes `f[0]=responsible_body:<id>`;
// a DOM-click flow on the controls produced exactly these URLs):
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const SOURCES = [
  {
    id: 'ADDR-003',
    url: 'https://ec.europa.eu/info/funding-tenders/opportunities/portal/screen/support/news?sortBy=publicationDate&order=DESC&pageNumber=1&pageSize=10&isExactMatch=false&programmes=Horizon%20Europe%20(HORIZON)',
    file: 'addr-003-news-filtered-rendered.html',
    criterion: 'programme = Horizon Europe (HORIZON)  [request facets[programme]=HORIZON]',
  },
  {
    id: 'ADDR-004',
    url: 'https://ec.europa.eu/info/funding-tenders/opportunities/portal/screen/support/events?isExactMatch=false&order=DESC&pageNumber=1&pageSize=50&sortBy=status&timeframe=past',
    file: 'addr-004-events-filtered-rendered.html',
    criterion: 'status = Past  [request facets[status]=PAST]',
  },
  {
    id: 'ADDR-005',
    url: 'https://hadea.ec.europa.eu/events_en?f%5B0%5D=oe_event_status%3Apast',
    file: 'addr-005-events-filtered-rendered.html',
    criterion: 'event status = Past  [request facets[status]=past]',
  },
  {
    id: 'ADDR-006',
    url: 'https://hadea.ec.europa.eu/news_en?f%5B0%5D=programmes_programme%3A9',
    file: 'addr-006-news-filtered-rendered.html',
    criterion: 'programme = EU4Health  [request programme=EU4Health]',
  },
  {
    id: 'ADDR-007',
    url: 'https://www.ema.europa.eu/en/news?f%5B0%5D=responsible_body%3A100002',
    file: 'addr-007-news-filtered-rendered.html',
    criterion: 'responsible body = CHMP  [request responsible_body=CHMP]',
  },
  {
    id: 'ADDR-008',
    url: 'https://www.ema.europa.eu/en/events/upcoming-events?f%5B0%5D=categories%3A83',
    file: 'addr-008-events-filtered-rendered.html',
    criterion: 'category = Human  [request facets[category]=human]',
  },
];

(async () => {
  const outdir = process.argv[2] || '.';
  const only = process.argv.slice(3);
  fs.mkdirSync(outdir, { recursive: true });
  const browser = await chromium.launch({ headless: true });
  for (const src of SOURCES) {
    if (only.length && !only.includes(src.id)) continue;
    const page = await browser.newPage({
      userAgent: 'Mozilla/5.0 (X11; Linux x86_64) NEIS-MVP-fetch',
      viewport: { width: 1280, height: 900 },
    });
    try {
      await page.goto(src.url, { waitUntil: 'networkidle', timeout: 90000 });
      // Let late XHR-driven rendering settle (portal lists, facet AJAX).
      await page.waitForTimeout(4000);
      const html = await page.content();
      const out = path.join(outdir, src.file);
      fs.writeFileSync(out, html, 'utf8');
      console.log(`${src.id}: criterion [${src.criterion}] `
          + `${html.length} bytes -> ${out}`);
    } catch (e) {
      console.error(`${src.id}: FAILED - ${e.message.split('\n')[0]}`);
      process.exitCode = 1;
    } finally {
      await page.close();
    }
  }
  await browser.close();
})();
