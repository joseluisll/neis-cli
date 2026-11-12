package es.jllopez.neis.domain.service;

import es.jllopez.neis.domain.model.FilterDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Domain service: generic, page-agnostic heuristics for the FILTER
 * capability (US-17). It knows nothing about any particular site — it
 * only recognizes the standard HTML5 shapes of exposed filter controls
 * (a form containing select dropdowns) and parses {@code <select>}
 * fields with their options. AI detection is the primary mechanism;
 * this service is only the fallback when no AI is configured.
 */
public class ExposedFilterDetector {

    /** A form element anywhere in the page. */
    private static final Pattern FORM = Pattern.compile(
            "<form\\b", Pattern.CASE_INSENSITIVE);

    /** A select dropdown anywhere in the page. */
    private static final Pattern SELECT = Pattern.compile(
            "<select\\b", Pattern.CASE_INSENSITIVE);

    /** One {@code <select ...> ... </select>} block (name optional). */
    private static final Pattern SELECT_BLOCK = Pattern.compile(
            "<select\\b([^>]*)>(.*?)</select>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern NAME_ATTR = Pattern.compile(
            "\\bname=\"([^\"]*)\"");

    private static final Pattern OPTION_TEXT = Pattern.compile(
            "<option\\b[^>]*>\\s*([^<]+?)\\s*</option>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern ARIA_LABEL = Pattern.compile(
            "aria-label=\"([^\"]+)\"");

    /** A {@code <label for="<select id>">} — the control's visible name. */
    private static final Pattern LABEL_FOR = Pattern.compile(
            "<label\\b[^>]*for=\"([^\"]+)\"[^>]*>(.*?)</label>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Any {@code <label>} (fallback: the nearest one before the select). */
    private static final Pattern LABEL = Pattern.compile(
            "<label\\b[^>]*>(.*?)</label>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern ID_ATTR = Pattern.compile("\\bid=\"([^\"]*)\"");

    /**
     * True when the page shows the standard shape of an exposed filter
     * UI: form(s) containing select dropdowns.
     */
    public boolean detect(String html) {
        if (html == null || html.isBlank()) {
            return false;
        }
        return FORM.matcher(html).find() && SELECT.matcher(html).find();
    }

    /**
     * Parses every {@code <select>} of the page into a
     * {@link FilterDefinition} (generic HTML5 parsing, no site knowledge).
     */
    public List<FilterDefinition> parseSelects(String html) {
        List<FilterDefinition> filters = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return filters;
        }
        Matcher blocks = SELECT_BLOCK.matcher(html);
        while (blocks.find()) {
            String attrs = blocks.group(1);
            String name = null;
            Matcher nameAttr = NAME_ATTR.matcher(attrs);
            if (nameAttr.find()) {
                name = nameAttr.group(1);
            }
            Matcher label = ARIA_LABEL.matcher(blocks.group(0));
            if (label.find()) {
                name = label.group(1);
            }
            // The control's VISIBLE name (the AI names fields by their
            // labels, so the generic parser must too — a chain's filter
            // spec is written against these names): the <label for=select
            // id> when present, else the nearest preceding <label>.
            String visibleName = labelFor(html, blocks.start(), attrs);
            if (visibleName != null && !visibleName.isBlank()) {
                name = visibleName;
            }
            List<String> values = new ArrayList<>();
            Matcher options = OPTION_TEXT.matcher(blocks.group(2));
            while (options.find()) {
                String value = options.group(1);
                if (!value.isBlank() && !values.contains(value)) {
                    values.add(value);
                }
            }
            values.removeIf(v -> v.equalsIgnoreCase("select")
                    || v.equalsIgnoreCase("choose") || v.equalsIgnoreCase("-"));
            filters.add(new FilterDefinition(
                    name == null || name.isBlank() ? "(unnamed select)" : name,
                    values));
        }
        return filters;
    }

    /**
     * The visible label of a select: the {@code <label for="<id>">} that
     * names it, or the nearest {@code <label>} before it (the label
     * precedes the control in the standard filter-form shapes). Inner
     * markup (spans) is stripped; null when nothing sensible is found.
     */
    private String labelFor(String html, int selectStart, String selectAttrs) {
        Matcher idAttr = ID_ATTR.matcher(selectAttrs);
        if (idAttr.find()) {
            String id = idAttr.group(1);
            Matcher labels = LABEL_FOR.matcher(html);
            while (labels.find()) {
                if (labels.group(1).equals(id)) {
                    return stripTags(labels.group(2));
                }
            }
        }
        String before = html.substring(Math.max(0, selectStart - 800), selectStart);
        Matcher labels = LABEL.matcher(before);
        String last = null;
        while (labels.find()) {
            last = labels.group(1);
        }
        return last == null ? null : stripTags(last);
    }

    private String stripTags(String html) {
        String text = html.replaceAll("<[^>]+>", " ")
                .replace("&amp;", "&").trim()
                .replaceAll("\\s+", " ");
        return text.isBlank() || text.length() > 60 ? null : text;
    }
}
