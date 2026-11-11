# MVP static samples — provenance

The four HTML5 fixtures in this directory are **real pages** of the
verified seed addresses of issue #62, captured on **2026-11-11** with a
real browser (Node + Playwright/Chromium, driven by
`scripts/fetch-rendered-html.js`: navigate, wait for network idle plus a
3s settle, then serialize the rendered DOM).

| Fixture | Address | Rendered size | Notes |
|---|---|---|---|
| `addr-001-news.html` | ADDR-001 EU Digital Identity Wallet — News | ~548 KB | Confluence; items are `li.js-news-item` in `ul.news-list` |
| `addr-002-events.html` | ADDR-002 EU Digital Identity Wallet — Events | ~489 KB | Confluence events listing |
| `addr-003-news-spa-rendered.html` | ADDR-003 Funding and Tenders Portal — Support News | ~348 KB | Angular SPA; plain HTTP fetch yields an empty shell — this is the Playwright-rendered DOM. Items: `sedia-result-card-news-translation` cards; paginated (`pageNumber`) |
| `addr-004-events-spa-rendered.html` | ADDR-004 Funding and Tenders Portal — Support Events | ~443 KB | Angular SPA, rendered as above. Event cards carry `ecl-date-block` dates; paginated |

To refresh the fixtures from the live sources:

```bash
npm i playwright && npx playwright install chromium
node scripts/fetch-rendered-html.js src/main/resources/mvp/samples
```

(Playwright is used here as the offline capture tool for the MVP's
static data; the production JS-rendering connector behind the SCRAPE
port is Playwright for Java, US-06.1.)
