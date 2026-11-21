package es.jllopez.neis.domain.service;

import es.jllopez.neis.domain.model.FilterDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Domain service: generic, page-agnostic heuristics for the FILTER
 * capability (US-17, extended by US-06.19/#150). It knows nothing about
 * any particular site — it only recognizes the standard HTML5 shapes of
 * exposed filter controls: forms with {@code <select>} dropdowns,
 * CHECKBOX/RADIO facet groups (the client-rendered panel shape, grouped
 * by shared id prefix) and labeled free-text inputs. AI detection is
 * the primary mechanism; this service is only the fallback when no AI
 * is configured.
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

    /** One {@code <input ...>} tag (attribute order varies). */
    private static final Pattern INPUT_TAG = Pattern.compile(
            "<input\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    private static final Pattern TYPE_ATTR = Pattern.compile(
            "\\btype=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    private static final Pattern VALUE_ATTR = Pattern.compile(
            "\\bvalue=\"([^\"]*)\"");

    private static final Pattern PLACEHOLDER_ATTR = Pattern.compile(
            "\\bplaceholder=\"([^\"]*)\"");

    /**
     * Leading id particles that carry no field meaning — a machine
     * generated facet id reads {@code checkbox-<group>-<value>}, so the
     * GROUP name is what remains after the value segment and these
     * particles are stripped (generic HTML5 idioms, no site knowledge).
     */
    private static final String[] ID_PARTICLES =
            {"checkbox", "chk", "filter", "input", "radio", "f", "c"};

    /**
     * Parses EVERY generic exposed filter shape of the page (US-06.19,
     * issue #150): {@code <select>} dropdowns, CHECKBOX/RADIO groups
     * (the facet-panel shape of client-rendered listings — grouped by
     * their shared id prefix, named by what remains, values from their
     * labels) and labeled free-text inputs (empty values list, per the
     * FilterDefinition convention). Page-agnostic HTML5 semantics only.
     */
    public List<FilterDefinition> parse(String html) {
        if (html == null || html.isBlank()) {
            return new ArrayList<>();
        }
        List<FilterDefinition> filters = new ArrayList<>(parseSelects(html));
        filters.addAll(parseCheckboxGroups(html));
        filters.addAll(parseTextInputs(html));
        return filters;
    }

    /**
     * The checkbox/radio facet groups of the page: every labeled
     * checkbox (or radio) joins the group its id prefix names
     * ({@code checkbox-status-upcoming} → "Status"); radios group by
     * their {@code name} attribute (the standard HTML form semantics).
     * A checkbox with no usable label is skipped — it names nothing.
     */
    private List<FilterDefinition> parseCheckboxGroups(String html) {
        // first-appearance order of the groups
        java.util.LinkedHashMap<String, GroupBuilder> groups = new java.util.LinkedHashMap<>();
        Matcher inputs = INPUT_TAG.matcher(html);
        while (inputs.find()) {
            String tag = inputs.group();
            String type = attrValue(tag, TYPE_ATTR);
            boolean checkbox = "checkbox".equalsIgnoreCase(type);
            boolean radio = "radio".equalsIgnoreCase(type);
            if (!checkbox && !radio) {
                continue;
            }
            String id = attrValue(tag, ID_ATTR);
            String name = attrValue(tag, NAME_ATTR);
            String label = labelForId(html, id);
            if (label == null) {
                label = attrValue(tag, ARIA_LABEL);
            }
            if (label == null) {
                label = attrValue(tag, VALUE_ATTR);
            }
            if (label == null || label.isBlank() || label.length() > 60) {
                continue;
            }
            String groupKey;
            String groupName;
            if (radio && name != null && !name.isBlank()) {
                groupKey = "name:" + name.toLowerCase(java.util.Locale.ROOT);
                groupName = humanize(name);
            } else if (id != null && id.contains("-")) {
                String prefix = id.substring(0, id.lastIndexOf('-'));
                groupKey = "id:" + prefix.toLowerCase(java.util.Locale.ROOT);
                groupName = humanize(prefix);
            } else {
                groupKey = "id:";
                groupName = "(checkbox group)";
            }
            groups.computeIfAbsent(groupKey,
                    k -> new GroupBuilder(groupName)).add(label);
        }
        List<FilterDefinition> filters = new ArrayList<>();
        for (GroupBuilder g : groups.values()) {
            filters.add(new FilterDefinition(g.name, g.values));
        }
        return filters;
    }

    /**
     * The labeled free-text filter inputs ({@code text}/{@code search}
     * or a type-less input — never password/hidden/submit shapes): the
     * name comes from the label, aria-label or placeholder; the values
     * list stays EMPTY (the FilterDefinition convention for free text).
     */
    private List<FilterDefinition> parseTextInputs(String html) {
        List<FilterDefinition> filters = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        Matcher inputs = INPUT_TAG.matcher(html);
        while (inputs.find()) {
            String tag = inputs.group();
            String type = attrValue(tag, TYPE_ATTR);
            boolean text = type == null || type.isBlank()
                    || "text".equalsIgnoreCase(type)
                    || "search".equalsIgnoreCase(type);
            if (!text) {
                continue;
            }
            String id = attrValue(tag, ID_ATTR);
            String name = labelForId(html, id);
            if (name == null) {
                name = attrValue(tag, ARIA_LABEL);
            }
            if (name == null) {
                name = attrValue(tag, PLACEHOLDER_ATTR);
            }
            if (name == null || name.isBlank() || name.length() > 60) {
                continue;
            }
            if (seen.add(name.toLowerCase(java.util.Locale.ROOT))) {
                filters.add(new FilterDefinition(name, List.of()));
            }
        }
        return filters;
    }

    /** The value of one single-quoted-attribute regex over a tag. */
    private static String attrValue(String tag, Pattern pattern) {
        Matcher m = pattern.matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    /**
     * The human-readable group name of a machine generated facet id
     * prefix: the last segment survives the generic particles ("checkbox-
     * status" → "Status"); a prefix with nothing left keeps the whole
     * prefix. Never carries site-specific vocabulary — the particles
     * above are HTML form idioms.
     */
    private static String humanize(String prefix) {
        String[] segments = prefix.toLowerCase(java.util.Locale.ROOT)
                .split("[-_]");
        String name = null;
        for (int i = segments.length - 1; i >= 0; i--) {
            if (!isParticle(segments[i])) {
                name = segments[i];
                break;
            }
        }
        if (name == null) {
            name = String.join(" ", segments);
        }
        if (name.isBlank()) {
            return "(checkbox group)";
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static boolean isParticle(String segment) {
        for (String p : ID_PARTICLES) {
            if (p.equals(segment)) {
                return true;
            }
        }
        return false;
    }

    /** Mutable group under construction (name + ordered unique values). */
    private static final class GroupBuilder {
        final String name;
        final List<String> values = new ArrayList<>();

        GroupBuilder(String name) {
            this.name = name;
        }

        void add(String value) {
            if (!values.contains(value)) {
                values.add(value);
            }
        }
    }

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

    /**
     * The visible label of a control: the {@code <label for="<id>">}
     * that names it exactly — used for the INPUT shapes (checkbox/radio
     * facet groups and free-text inputs), where the selects-only
     * nearest-preceding-label fallback would misname a control with the
     * PREVIOUS group's label (facet panels pack many controls tightly).
     */
    private String labelForId(String html, String id) {
        if (id == null) {
            return null;
        }
        Matcher labels = LABEL_FOR.matcher(html);
        while (labels.find()) {
            if (labels.group(1).equals(id)) {
                return stripTags(labels.group(2));
            }
        }
        return null;
    }

    private String stripTags(String html) {
        String text = html.replaceAll("<[^>]+>", " ")
                .replace("&amp;", "&").trim()
                .replaceAll("\\s+", " ");
        return text.isBlank() || text.length() > 60 ? null : text;
    }
}
