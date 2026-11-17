package es.jllopez.neis.infrastructure.adapter.scrape;

import es.jllopez.neis.domain.model.SimplifiedContent;
import es.jllopez.neis.domain.port.SimplifyPort;
import net.dankito.readability4j.Article;
import net.dankito.readability4j.Readability4J;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Router connector (driven adapter) implementing the SIMPLIFY port
 * (US-07.5, issue #87): routes each page to the extractor that fits its
 * structure, behind the SAME content port (US-01.4 replaceability):
 *
 * <ul>
 *   <li><b>Article-like pages</b> — {@link Readability4J} (Apache 2.0,
 *   Java port of Mozilla Readability) detects the main content region
 *   (title, byline, body) and removes the boilerplate around it; the
 *   extracted article is then handed through the SAME
 *   jsoup action/data-element cleanse as every other page, so the
 *   output contract (US-07.1..US-07.4: no scripts/styles/media,
 *   syntactically correct, &lt;= 100 KB) holds regardless of the
 *   extractor. Readability removes CONTENT too (boilerplate, repeated
 *   link lists) — that is its purpose; the jsoup cleanse then reduces
 *   what remains to action and data elements.</li>
 *   <li><b>Non-article structures</b> (listings, tables, filter
 *   grids) — the existing {@link JsoupSimplifyConnector} Safelist rules
 *   directly, with no readability pass: Readability's heuristics score
 *   text-heavy articles and would mis-fire on a listing.</li>
 * </ul>
 *
 * <p>The article-like decision is a deterministic structure probe (no
 * site-specific knowledge): the page carries a semantic article marker
 * ({@code <article>}, {@code [itemtype*=schema.org/NewsArticle]},
 * {@code [role=article]}, a schema.org BlogPosting/NewsArticle JSON-LD
 * block) AND reads as prose rather than a listing — a listing carries
 * many similar item containers/tables and little paragraph text, an
 * article carries substantial paragraph text. When Readability itself
 * cannot isolate a main region (its parse yields no content), the
 * router falls back to the jsoup cleanse too — never a failed run for
 * using the primary extractor.</p>
 */
@Component
@org.springframework.context.annotation.Primary
public class ReadabilitySimplifyRouter implements SimplifyPort {

    private static final Logger log =
            LoggerFactory.getLogger(ReadabilitySimplifyRouter.class);

    /**
     * The sentinel base for pages without a usable declared {@code
     * <base href>}: an origin plus a PATH segment (review round-2
     * finding B2 — a bare origin made root-relative links lose their
     * leading slash when the absolute prefix was stripped). The
     * sentinel NEVER reaches the output: the fabricated prefixes are
     * stripped back to relative in simplifyWithReadability.
     */
    private static final String SENTINEL_BASE = "https://neis.invalid/__b__/";

    private final JsoupSimplifyConnector jsoupCleanse;

    public ReadabilitySimplifyRouter(JsoupSimplifyConnector jsoupCleanse) {
        this.jsoupCleanse = jsoupCleanse;
    }

    @Override
    public SimplifiedContent simplify(String rawHtml) {
        return simplify(rawHtml, null);
    }

    /**
     * Same routing, with the address's classified webpage type (the
     * caller may pass it; null = type unknown, the structure probe
     * decides alone). Feed-type pages never reach this port (parsed by
     * Rome, US-06.8), so no feed handling here.
     */
    public SimplifiedContent simplifyWithType(String rawHtml, String webpageType) {
        if (isArticleLike(rawHtml)) {
            SimplifiedContent article = simplifyWithReadability(rawHtml);
            if (article != null) {
                return article;
            }
            log.info("SIMPLIFY: Readability4J produced no content — "
                    + "falling back to the jsoup Safelist rules");
        }
        return jsoupCleanse.simplify(rawHtml);
    }

    /**
     * US-07.6: the tiered rendering. The router routes the FULL tier
     * per page structure (article vs non-article); the economy/lean
     * tiers render from the pruned element tree of the SAME page via
     * the jsoup connector's tiered path — the tier is a reduction of
     * the tree, not a different extractor.
     */
    @Override
    public SimplifiedContent simplify(String rawHtml, String variant) {
        // Normalize BEFORE the branch: the param may be null/blank (the
        // 1-arg overload and the no-variant call sites delegate here
        // with null) — checking the RAW value against FULL sent the
        // default tier down the jsoup connector's path, BYPASSING the
        // Readability routing entirely (probe-verified: the 1-arg
        // simplify returned the cleanse output, the article title h1
        // never re-attached). Null/blank == full.
        String tier = variant == null || variant.isBlank()
                ? es.jllopez.neis.domain.model.SimplifyVariant.FULL
                : variant.trim();
        new es.jllopez.neis.domain.model.SimplifyVariant(tier); // validate
        if (es.jllopez.neis.domain.model.SimplifyVariant.FULL.equals(tier)) {
            return simplifyWithType(rawHtml, null);
        }
        return jsoupCleanse.simplify(rawHtml, tier);
    }

    /** Port-level US-07.4 contract: both extractors carry the check. */
    @Override
    public void verifySyntax(String html) {
        jsoupCleanse.verifySyntax(html);
    }

    /**
     * The article-like probe (deterministic, structure-only): a
     * semantic article marker AND prose dominance (substantial
     * paragraph text relative to the page's text — a listing's text
     * lives in links/table cells, an article's in paragraphs).
     */
    boolean isArticleLike(String html) {
        if (html == null || html.isBlank()) {
            return false;
        }
        Document doc = Jsoup.parse(html);
        boolean hasArticleMarker =
                !doc.select("article, [role=article], "
                        + "[itemtype*=schema.org/NewsArticle], "
                        + "[itemtype*=schema.org/BlogPosting], "
                        + "[itemtype*=schema.org/Article]").isEmpty()
                || doc.select("script[type*=ld+json]").stream().anyMatch(s ->
                        s.data().contains("BlogPosting")
                                || s.data().contains("NewsArticle")
                                || s.data().contains("\"@type\":\"Article\""));
        if (!hasArticleMarker) {
            return false;
        }
        // US-07.5 review finding B1: many similar article containers is
        // a LISTING built from teaser cards (the common news-listing
        // pattern) — Readability would score one teaser as "the
        // article" and drop the rest of the items. An article page has
        // ONE main content region.
        int articleCount = doc.select("article, [role=article]").size();
        if (articleCount > 1) {
            return false;
        }
        // Prose dominance: paragraph text vs. total text (a listing
        // keeps most of its text inside links/table cells).
        String paragraphText = doc.select("p").text();
        String totalText = doc.body() == null ? "" : doc.body().text();
        if (totalText.length() < 400) {
            return false; // too small to be an article worth extracting
        }
        return paragraphText.length() * 2 >= totalText.length();
    }

    /**
     * The Readability pass: extract the article, then run the SAME
     * jsoup cleanse over the extracted region (the output contract is
     * the connector's, not the extractor's). Returns null when
     * Readability cannot isolate a main region — the router falls back.
     */
    private SimplifiedContent simplifyWithReadability(String rawHtml) {
        try {
            String base = baseUri(rawHtml);
            Readability4J readability = new Readability4J(base, rawHtml);
            Article article = readability.parse();
            org.jsoup.nodes.Element content = article.getArticleContent();
            if (content == null || content.text().isBlank()) {
                return null;
            }
            // US-07.5 review finding B2: Readability absolutises
            // relative URLs against the base URI — without a declared
            // base that made up https://localhost/... links, corrupting
            // the detail URLs downstream code resolves against the real
            // address. Strip the fabricated absolute prefix so the
            // hrefs stay RELATIVE (exactly what the jsoup path emits);
            // an explicit <base href> still resolves for real.
            if (SENTINEL_BASE.equals(base)) {
                stripFabricatedAbsoluteUrls(content, base);
            }
            // The content element goes INSIDE a body (review finding
            // B3: appended to a fresh Document it sat outside
            // <html><body>, so the title check read an empty body and
            // the h1 always fired, landing after the content).
            Document articleDoc = new Document(base);
            org.jsoup.nodes.Element body = articleDoc.appendElement("body");
            body.appendChild(content.clone());
            // The article title is data (US-07.2): re-attach it as an
            // h1 when Readability dropped it from the content region.
            String title = article.getTitle();
            if (title != null && !title.isBlank()
                    && !articleDoc.text().contains(title)) {
                org.jsoup.nodes.Element h1 =
                        articleDoc.createElement("h1").text(title.trim());
                body.prependChild(h1);
            }
            return jsoupCleanse.simplify(articleDoc.html());
        } catch (RuntimeException e) {
            log.info("SIMPLIFY: Readability4J failed ({}) — falling back "
                    + "to the jsoup Safelist rules", e.getMessage());
            return null;
        }
    }

    /** True when the raw HTML declares its own {@code <base href>}. */
    private boolean hasDeclaredBase(String html) {
        Document doc = Jsoup.parse(html);
        return doc.selectFirst("base[href]") != null;
    }

    /**
     * Undo Readability's URL absolutisation for links it resolved
     * against the GIVEN base: values equal to base + a relative path
     * are rewritten back to the relative path. Anything else (already
     * absolute on the page, another host) is left untouched.
     */
    private void stripFabricatedAbsoluteUrls(org.jsoup.nodes.Element content,
                                             String base) {
        String withPath = base.endsWith("/") ? base : base + "/";
        String bareOrigin = withPath.substring(0,
                withPath.indexOf('/', withPath.indexOf("//") + 2));
        for (org.jsoup.nodes.Element el : content.select("[href], [src], [action]")) {
            for (String attr : new String[]{"href", "src", "action"}) {
                if (!el.hasAttr(attr)) {
                    continue;
                }
                String value = el.attr(attr);
                if (value.startsWith(withPath)) {
                    el.attr(attr, value.substring(withPath.length()));
                } else if (value.startsWith(bareOrigin)) {
                    el.attr(attr, value.substring(bareOrigin.length()));
                }
            }
        }
    }

    /**
     * A base URI for the parse (relative-URL resolution): the raw
     * HTML's declared {@code <base href>}, else a neutral placeholder
     * (the placeholder NEVER reaches the output — the fabricated
     * absolute URLs are stripped back to relative above).
     */
    private String baseUri(String html) {
        try {
            Document doc = Jsoup.parse(html);
            org.jsoup.nodes.Element base = doc.selectFirst("base[href]");
            if (base != null) {
                String href = base.attr("href").trim();
                if (!href.isBlank() && URI.create(href).isAbsolute()) {
                    return href;
                }
            }
        } catch (RuntimeException e) {
            // fall through to the sentinel
        }
        return SENTINEL_BASE;
    }
}
