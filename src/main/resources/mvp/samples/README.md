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

## Captured FILTERED responses (US-06.6 slice, #73)

The `addr-00X-*-filtered-rendered.html` fixtures are **real filtered
pages** of the six FILTERING-flagged addresses, captured 2026-11-11 with
the same real browser via `scripts/fetch-filtered-html.js`: the listing
is loaded with ONE filter criterion of the site's OWN filter mechanism
expressed the way the site itself expresses it (URL facet/filters
parameters, discovered by operating the real controls in a browser).
Each fixture stands in the MVP SCRAPE connector for the site's response
to the filter request the chain's apply step sends; the criterion each
embodies is reported by the connector as its supported filter request.

| Fixture | Address | Applied criterion | Site request |
|---|---|---|---|
| `addr-003-news-filtered-rendered.html` | ADDR-003 Support News | programme = Horizon Europe (HORIZON) | `programmes=Horizon Europe (HORIZON)` |
| `addr-004-events-filtered-rendered.html` | ADDR-004 Support Events | status = Past | `timeframe=past` |
| `addr-005-events-filtered-rendered.html` | ADDR-005 HaDEA Events | event status = Past | `f[0]=oe_event_status:past` |
| `addr-006-news-filtered-rendered.html` | ADDR-006 HaDEA News | programme = EU4Health | `f[0]=programmes_programme:9` |
| `addr-007-news-filtered-rendered.html` | ADDR-007 EMA News | responsible body = CHMP | `f[0]=responsible_body:100002` |
| `addr-008-events-filtered-rendered.html` | ADDR-008 EMA Upcoming Events | category = Human | `f[0]=categories:83` |

The criteria are the ones the chain's apply step chose (one per
address); ADDR-001/002/009 expose no filter controls and are reported
skipped by the chain. Refresh with:

```bash
NODE_PATH=<npx-cache>/node_modules node scripts/fetch-filtered-html.js src/main/resources/mvp/samples
```
