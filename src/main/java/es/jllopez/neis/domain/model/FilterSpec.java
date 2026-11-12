package es.jllopez.neis.domain.model;

/**
 * A filter criterion a CHAIN carries in its definition (owner decision
 * 2026-11-12): which field of the source's filter identification to
 * apply and which value. The filter belongs to the chain, not to the
 * address — at run time the FILTER step coherence-matches it against
 * the address's stored filter catalogue (the AI-identified definitions)
 * and the SCRAPE connector's servable requests, fully offline.
 *
 * @param field the filter field, matched leniently against the
 *              catalogue names (case-insensitive containment)
 * @param value the value of that field to apply
 */
public record FilterSpec(String field, String value) {

    /** Parameter keys a FILTER chain step carries the spec in. */
    public static final String FIELD_KEY = "field";
    public static final String VALUE_KEY = "value";

    /**
     * The spec of a chain's FILTER step parameters, or null when the
     * step carries no spec (both keys absent). Exactly one key without
     * the other is a broken definition, not a silent half-spec.
     */
    public static FilterSpec of(java.util.Map<String, String> parameters) {
        String field = parameters.get(FIELD_KEY);
        String value = parameters.get(VALUE_KEY);
        if (field == null && value == null) {
            return null;
        }
        if (field == null || value == null || field.isBlank() || value.isBlank()) {
            throw new IllegalStateException(
                    "A FILTER chain step's filter spec needs both '"
                            + FIELD_KEY + "' and '" + VALUE_KEY + "' (got: " + parameters + ")");
        }
        return new FilterSpec(field.trim(), value.trim());
    }

    /**
     * Parses an inline spec from a chain-creation option:
     * {@code "field=X;value=Y"} (the same one-criterion shape the run
     * applies). Throws IllegalArgumentException on a malformed value.
     */
    public static FilterSpec parseInline(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("A filter spec cannot be empty");
        }
        String field = null;
        String value = null;
        for (String pair : raw.split(";")) {
            String entry = pair.trim();
            int equals = entry.indexOf('=');
            String key = equals <= 0 ? "" : entry.substring(0, equals).trim().toLowerCase();
            String val = equals <= 0 ? "" : entry.substring(equals + 1).trim();
            switch (key) {
                case "field" -> field = val;
                case "value" -> value = val;
                default -> throw new IllegalArgumentException(
                        "A filter spec is 'field=<name>;value=<value>', got: '" + entry + "'");
            }
        }
        if (field == null || field.isBlank() || value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "A filter spec needs BOTH field and value, got: '" + raw + "'");
        }
        return new FilterSpec(field, value);
    }

    /**
     * Parses a structured spec from a JSON document — the common shape
     * the platform itself reports: either one criterion
     * {@code {"field": ..., "value": ...}} or a list
     * {@code {"filters": [{"field": ..., "value": ...}, ...]}} (the
     * FIRST criterion is applied; single-criterion application is the
     * US-06.6 decision). Lenient parse; throws IllegalArgumentException
     * when no criterion can be read.
     */
    public static FilterSpec fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("A filter spec JSON cannot be empty");
        }
        String field = jsonField(json, FIELD_KEY);
        String value = jsonField(json, VALUE_KEY);
        if (field == null || value == null) {
            // {"filters":[...]}: first criterion of the list.
            int arrayStart = json.indexOf("\"filters\"");
            if (arrayStart >= 0) {
                int braceStart = json.indexOf('{', arrayStart);
                if (braceStart >= 0) {
                    int depth = 0;
                    for (int i = braceStart; i < json.length(); i++) {
                        if (json.charAt(i) == '{') {
                            depth++;
                        } else if (json.charAt(i) == '}') {
                            if (depth == 1) {
                                String object = json.substring(braceStart, i + 1);
                                field = jsonField(object, FIELD_KEY);
                                value = jsonField(object, VALUE_KEY);
                                break;
                            }
                            depth--;
                        }
                    }
                }
            }
        }
        if (field == null || field.isBlank() || value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "A filter spec JSON needs a criterion with 'field' and 'value', got: "
                            + json.trim());
        }
        return new FilterSpec(field.trim(), value.trim());
    }

    /** Lenient scalar field read of a flat JSON object. */
    private static String jsonField(String json, String name) {
        if (json == null) {
            return null;
        }
        var m = java.util.regex.Pattern
                .compile("\"" + name + "\"\\s*:\\s*(null|\"([^\"]*)\")")
                .matcher(json);
        if (!m.find() || m.group(1).equals("null")) {
            return null;
        }
        return m.group(2);
    }

    /** Lenient, case-insensitive containment either direction. */
    public boolean matches(String candidate) {
        if (candidate == null) {
            return false;
        }
        String c = candidate.toLowerCase();
        return c.contains(field.toLowerCase()) || field.toLowerCase().contains(c)
                || c.contains(value.toLowerCase()) || value.toLowerCase().contains(c);
    }
}
