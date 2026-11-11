package es.jllopez.neis.infrastructure.adapter.scrape;

import es.jllopez.neis.domain.model.NewsEventItem;
import es.jllopez.neis.domain.port.SelectorExtractionPort;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Connector (driven adapter) implementing selector-based extraction with
 * jsoup (owner-confirmed stack, US-12.2): deterministic iteration over
 * the elements matched by a CSS selector, collecting title, link and
 * date metadata per item.
 */
@Component
public class JsoupSelectorConnector implements SelectorExtractionPort {

    private static final Pattern DATE_PATTERN = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}"
                    + "|\\d{1,2}/\\d{1,2}/\\d{2,4}"
                    + "|\\d{1,2}\\s+(?:January|February|March|April|May|June|July|"
                    + "August|September|October|November|December)[a-z]*\\s+\\d{4}"
                    + "|\\d{1,2}\\s+(?:Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{4}"
                    + "|(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{1,2},?\\s+\\d{4}");

    @Override
    public List<NewsEventItem> extract(String html, String cssSelector, String kind,
                                       String addressId, int maxItems) {
        List<NewsEventItem> items = new ArrayList<>();
        if (cssSelector == null || cssSelector.isBlank()) {
            return items;
        }
        Document doc = Jsoup.parse(html);
        Elements matched;
        try {
            matched = doc.select(cssSelector);
        } catch (IllegalArgumentException e) {
            return items; // invalid selector -> no items (validation by extraction)
        }
        for (Element el : matched) {
            if (items.size() >= maxItems) {
                break;
            }
            String title = extractTitle(el);
            String url = extractUrl(el);
            if (title.isBlank() && url == null) {
                continue; // not a meaningful item
            }
            items.add(new NewsEventItem(addressId, kind, title, extractDescription(el),
                    url, extractDate(el)));
        }
        return items;
    }

    private String extractTitle(Element el) {
        Element heading = el.selectFirst("h1, h2, h3, h4, h5");
        String title = heading != null ? heading.text() : el.text();
        title = title == null ? "" : title.trim();
        return title.length() > 200 ? title.substring(0, 200) + "..." : title;
    }

    /** Summary text of the item, when the listing provides one (US-06.4). */
    private String extractDescription(Element el) {
        Element paragraph = el.selectFirst("p");
        String text = paragraph == null ? null : paragraph.text();
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }

    private String extractUrl(Element el) {
        Element link = el.is("a[href]") ? el : el.selectFirst("a[href]");
        return link != null ? link.attr("abs:href").isBlank()
                ? link.attr("href") : link.attr("abs:href") : null;
    }

    private String extractDate(Element el) {
        Element time = el.selectFirst("time[datetime]");
        if (time != null && !time.attr("datetime").isBlank()) {
            return time.attr("datetime");
        }
        Matcher m = DATE_PATTERN.matcher(el.text());
        return m.find() ? m.group() : null;
    }
}
