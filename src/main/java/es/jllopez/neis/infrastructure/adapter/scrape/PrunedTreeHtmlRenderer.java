package es.jllopez.neis.infrastructure.adapter.scrape;

import es.jllopez.neis.domain.model.PrunedElement;
import es.jllopez.neis.domain.model.SimplifiedContent;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Connector (driven adapter): renders simplified HTML5 FROM THE PRUNED
 * ELEMENT TREE (US-07.6, issue #89) — Skyvern's json_to_html concept
 * re-implemented in Java (NO Skyvern code copied, AGPL respected).
 * The input is the pruned document (the US-06.9 prune semantics
 * applied by {@link JsoupDomAnalyzer#prune}: dropped non-interactable
 * elements gone, dropped interactable ones bare); the output is
 * compact, syntactically correct HTML5 at the requested tier:
 *
 * <ul>
 *   <li><b>full</b> — every pruned-tree element with its whitelisted
 *   attributes and text.</li>
 *   <li><b>economy</b> — a percentage/size target (default 60% of the
 *   full rendering): the longest texts truncate first, then textless
 *   presentational containers (divs/spans with no text, no
 *   interactable descendant) drop until the target fits — the
 *   AI-analysis budget tier.</li>
 *   <li><b>lean</b> — long hrefs/srcs compressed into hashed
 *   placeholders with a side table, query strings stripped,
   non-navigable links (no href or a non-resolving one) collapsed to
 *   their text.</li>
 * </ul>
 *
 * <p>Pseudo-element preservation (US-07.6): texts contributed by CSS
 * {@code ::before}/{@code ::after} never reach a jsoup DOM (they live
 * in stylesheets), so icon-only buttons would render empty. The
 * renderer instead keeps the ARIA text of such elements: an
 * icon-only button/link (no text) carries its {@code aria-label} /
 * {@code title} as real text content, so the label survives the
 * rendering (US-07.4 data preservation).</p>
 *
 * <p>Hashed-URL side tables (US-11.4): a lean-tier URL longer than
 * {@link PrunedElement#MAX_URL_LENGTH} is replaced by a stable
 * {@code hash:<12 hex>} placeholder; the mapping placeholder → full
 * URL is returned alongside the rendering for the webpage metadata
 * (never-wipes rule).</p>
 */
@Component
public class PrunedTreeHtmlRenderer {

    /** Economy tier default: the rendering target as a share of full. */
    static final double DEFAULT_ECONOMY_FRACTION = 0.60;

    /** Query strings are stripped in the lean tier (tracking noise). */
    private static final int LEAN_MAX_URL = PrunedElement.MAX_URL_LENGTH;

    /**
     * Renders the pruned tree at the tier. The returned content's HTML
     * is syntactically correct HTML5 by construction (jsoup
     * serialisation of a parsed DOM); the caller still runs the
     * US-07.4 compliance check on the exact bytes.
     */
    public SimplifiedContent render(Document prunedDoc, String variant) {
        Document doc = prunedDoc.clone();
        applyPseudoElementTextPreservation(doc);
        Map<String, String> placeholders = new LinkedHashMap<>();
        switch (variant) {
            case "lean" -> applyLean(doc, placeholders);
            case "economy" -> applyEconomy(doc);
            case "full" -> { /* the pruned tree as-is */ }
            default -> throw new IllegalArgumentException(
                    "Unknown SIMPLIFY variant: " + variant);
        }
        doc.outputSettings().prettyPrint(false)
                .charset(StandardCharsets.UTF_8);
        String html = doc.body() != null ? doc.body().html() : doc.html();
        return new SimplifiedContent(html, html.getBytes(StandardCharsets.UTF_8).length);
    }

    /**
     * Pseudo-element text preservation: an interactable element with NO
     * text but an aria-label/title gets that label as its real text —
     * the icon-only button/label keeps its meaning in the rendered
     * tree (the CSS pseudo-element text is invisible to the DOM; the
     * ARIA name is the accessible rendering of the same idea).
     */
    private void applyPseudoElementTextPreservation(Document doc) {
        for (Element el : doc.select("a, button, input, [role=button]")) {
            if (!el.text().isBlank() || el.children().size() > 0) {
                continue;
            }
            String label = !el.attr("aria-label").isBlank()
                    ? el.attr("aria-label")
                    : el.attr("title");
            if (!label.isBlank() && el.is("input")) {
                el.attr("value", label);
            } else if (!label.isBlank()) {
                el.text(label.trim());
            }
        }
    }

    /**
     * Lean tier: non-navigable links collapse to their text (a link
     * without href carries no action, its text is the data); query
     * strings stripped (tracking noise, not data); oversized URLs
     * hashed into the side table.
     */
    private void applyLean(Document doc, Map<String, String> placeholders) {
        for (Element a : doc.select("a")) {
            if (!a.hasAttr("href") || a.attr("href").isBlank()) {
                // non-navigable: keep the text, drop the dead link tag
                a.replaceWith(new Element("span").text(a.text()));
                continue;
            }
            String href = a.attr("href");
            int query = href.indexOf('?');
            if (query > 0) {
                // Lean tier strips query strings (tracking noise, not
                // data) — but PAGINATION params are data (US-17 page
                // iteration) and survive: /list?page=2&utm_source=x
                // renders as /list?page=2.
                a.attr("href", href.substring(0, query)
                        + keptPaginationQuery(href.substring(query + 1)));
            }
            if (href.length() > LEAN_MAX_URL) {
                String hash = sha256(href).substring(0, 12);
                String placeholder = "hash:" + hash;
                placeholders.putIfAbsent(placeholder, href);
                a.attr("href", placeholder);
            }
        }
    }

    /** Rebuilds a query string keeping ONLY the pagination params
     * (page=, p=, pageNumber=, page_index=): pagination is DATA (US-17
     * page iteration); tracking/utm noise is stripped in the lean tier.
     * Returns "" when no pagination param exists (the ? goes too). */
    static String keptPaginationQuery(String query) {
        StringBuilder kept = new StringBuilder();
        for (String param : query.split("&")) {
            if (param.isBlank()) {
                continue;
            }
            String name = param.contains("=")
                    ? param.substring(0, param.indexOf('=')).toLowerCase(Locale.ROOT)
                    : param.toLowerCase(Locale.ROOT);
            if (name.equals("page") || name.equals("p") || name.equals("pagenumber")
                    || name.equals("page_index")) {
                if (kept.length() > 0) {
                    kept.append('&');
                }
                kept.append(param);
            }
        }
        return kept.length() > 0 ? "?" + kept : "";
    }

    /**
     * Economy tier: reduce until the rendering fits the configured
     * fraction of the full rendering. Deterministic reduction: the
     * LONGEST texts truncate first (500-char cap, then 200, then 80),
     * then textless presentational containers drop (a div/span with no
     * text, no interactable descendant, no whitelisted action
     * attribute). The elements CARRY data by definition (the pruned
     * tree keeps data/action elements), so the reduction shortens
     * forms before it removes anything.
     */
    private void applyEconomy(Document doc) {
        doc.outputSettings().prettyPrint(false);
        double fraction = DEFAULT_ECONOMY_FRACTION;
        int target = (int) (sizeOf(doc) * fraction);
        // stage 1: truncate the longest texts
        for (int cap : new int[]{500, 200, 80}) {
            if (sizeOf(doc) <= target) {
                return;
            }
            for (Element el : doc.select("*")) {
                String text = el.ownText();
                if (text.length() > cap) {
                    el.text(text.substring(0, cap));
                }
            }
        }
        // stage 2: drop textless presentational containers
        boolean changed = true;
        while (changed && sizeOf(doc) > target) {
            changed = false;
            for (Element el : new ArrayList<>(doc.select("div, span"))) {
                if (sizeOf(doc) <= target) {
                    return;
                }
                if (el.ownerDocument() == null) {
                    continue; // already removed via an ancestor
                }
                if (el.text().isBlank() && el.select("a, button, input, "
                        + "select, textarea, form, table").isEmpty()
                        && el.attributes().size() <= 1) {
                    el.remove();
                    changed = true;
                    break;
                }
            }
        }
    }

    private int sizeOf(Document doc) {
        return (doc.body() != null ? doc.body().html() : doc.html())
                .getBytes(StandardCharsets.UTF_8).length;
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
