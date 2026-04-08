/*
 * XNAT DICOMweb Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.util;

import lombok.extern.slf4j.Slf4j;
import org.nrg.xnat.dicomweb.exceptions.NotAcceptableException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP content negotiation for DICOMweb endpoints per DICOM PS 3.18 Section 8.3.3.
 *
 * <p>Parses Accept headers into a quality-ranked list of media types and selects
 * the best match from a set of server-supported types. Also handles the DICOMweb
 * {@code accept} query parameter (Section 8.3.3.1), which has the same semantics
 * but disallows wildcards.</p>
 */
@Slf4j
public final class MediaTypeNegotiator {
    private MediaTypeNegotiator() {}

    /**
     * A parsed media type with optional parameters and quality value.
     */
    public static class ParsedMediaType implements Comparable<ParsedMediaType> {
        private final String type;
        private final String subtype;
        private final Map<String, String> parameters;
        private final double quality;
        private final String fullType; // "type/subtype" without parameters

        ParsedMediaType(String type, String subtype, Map<String, String> parameters, double quality) {
            this.type = type;
            this.subtype = subtype;
            this.parameters = parameters;
            this.quality = quality;
            this.fullType = type + "/" + subtype;
        }

        public String getType() { return type; }
        public String getSubtype() { return subtype; }
        public double getQuality() { return quality; }
        public String getFullType() { return fullType; }
        public Map<String, String> getParameters() { return parameters; }

        public String getParameter(String name) {
            return parameters.get(name.toLowerCase());
        }

        public boolean isWildcard() {
            return "*".equals(type) && "*".equals(subtype);
        }

        public boolean isSubtypeWildcard() {
            return "*".equals(subtype);
        }

        /**
         * Check whether this parsed type matches a concrete server type.
         * Wildcards in this (the client type) match anything.
         */
        public boolean matches(String serverType) {
            if (isWildcard()) {
                return true;
            }
            String[] parts = serverType.split("/", 2);
            if (parts.length != 2) {
                return false;
            }
            if (!type.equalsIgnoreCase(parts[0])) {
                return false;
            }
            return isSubtypeWildcard() || subtype.equalsIgnoreCase(parts[1]);
        }

        @Override
        public int compareTo(ParsedMediaType other) {
            // Higher quality first
            int cmp = Double.compare(other.quality, this.quality);
            if (cmp != 0) return cmp;
            // More specific types first (wildcard last)
            int thisSpecificity = isWildcard() ? 0 : isSubtypeWildcard() ? 1 : 2;
            int otherSpecificity = other.isWildcard() ? 0 : other.isSubtypeWildcard() ? 1 : 2;
            return Integer.compare(otherSpecificity, thisSpecificity);
        }

        @Override
        public String toString() {
            return fullType + (quality < 1.0 ? ";q=" + quality : "");
        }
    }

    /**
     * Parse an Accept header value into a sorted list of media types.
     *
     * @param acceptHeader the Accept header value, or null
     * @return list of parsed media types sorted by quality (descending), or empty list if null/blank
     */
    public static List<ParsedMediaType> parse(String acceptHeader) {
        if (acceptHeader == null || acceptHeader.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<ParsedMediaType> result = new ArrayList<>();

        // Split on commas that are outside quoted strings
        for (String range : splitAcceptHeader(acceptHeader)) {
            range = range.trim();
            if (range.isEmpty()) continue;

            try {
                result.add(parseMediaRange(range));
            } catch (IllegalArgumentException e) {
                log.debug("Skipping unparseable media range '{}': {}", range, e.getMessage());
            }
        }

        Collections.sort(result);
        return result;
    }

    /**
     * Select the best media type from a set of server-supported types,
     * given a parsed Accept list.
     *
     * <p>If the Accept list is empty (header absent/blank), the
     * {@code defaultType} is returned.  If no match is found and
     * {@code defaultType} is non-null, the default is returned (per PS 3.18:
     * resources with a defined default always fall back to it). If
     * {@code defaultType} is null and no match is found, throws
     * {@link NotAcceptableException}.</p>
     *
     * @param accepted     parsed Accept list (from {@link #parse})
     * @param supported    server-supported media types (order = preference)
     * @param defaultType  the default media type for this resource category, or null
     * @return the selected media type string (from {@code supported})
     */
    public static String negotiate(List<ParsedMediaType> accepted,
                                   List<String> supported,
                                   String defaultType) {
        if (accepted.isEmpty()) {
            // No Accept header — return default or first supported
            return defaultType != null ? defaultType : supported.get(0);
        }

        // For each accepted type (already sorted by quality), find the first
        // supported type that matches.
        for (ParsedMediaType clientType : accepted) {
            for (String serverType : supported) {
                if (clientType.matches(serverType)) {
                    return serverType;
                }
            }
        }

        // No match found
        if (defaultType != null) {
            return defaultType;
        }
        throw new NotAcceptableException(
                "None of the requested media types are supported. Supported: " + supported);
    }

    /**
     * Convenience method: parse the Accept header (or {@code accept} query param)
     * and negotiate in one call.
     *
     * @param acceptHeader  Accept header value (may be null)
     * @param acceptParam   {@code accept} query parameter value (may be null)
     * @param supported     server-supported media types
     * @param defaultType   default media type (null if resource has no default)
     * @return selected media type
     */
    public static String negotiate(String acceptHeader, String acceptParam,
                                   List<String> supported, String defaultType) {
        // Per PS 3.18 Section 8.3.3.1: accept query parameter takes precedence
        // and must not contain wildcards.
        String effective = acceptParam != null && !acceptParam.trim().isEmpty()
                ? acceptParam : acceptHeader;
        List<ParsedMediaType> parsed = parse(effective);

        if (acceptParam != null && !acceptParam.trim().isEmpty()) {
            for (ParsedMediaType mt : parsed) {
                if (mt.isWildcard() || mt.isSubtypeWildcard()) {
                    throw new org.nrg.xnat.dicomweb.exceptions.BadRequestException(
                            "accept", "wildcards are not permitted in the accept query parameter");
                }
            }
        }

        return negotiate(parsed, supported, defaultType);
    }

    // ---- internal helpers ----

    private static ParsedMediaType parseMediaRange(String range) {
        // Split into media type and parameters: "type/subtype;param=val;q=0.5"
        String[] parts = range.split(";");
        String typeSlash = parts[0].trim();

        String[] typeSubtype = typeSlash.split("/", 2);
        if (typeSubtype.length != 2) {
            throw new IllegalArgumentException("Invalid media type: " + typeSlash);
        }

        String type = typeSubtype[0].trim();
        String subtype = typeSubtype[1].trim();
        double quality = 1.0;
        Map<String, String> parameters = new LinkedHashMap<>();

        for (int i = 1; i < parts.length; i++) {
            String param = parts[i].trim();
            int eq = param.indexOf('=');
            if (eq < 0) continue;
            String key = param.substring(0, eq).trim().toLowerCase();
            String val = param.substring(eq + 1).trim();
            // Strip quotes
            if (val.length() >= 2 && val.startsWith("\"") && val.endsWith("\"")) {
                val = val.substring(1, val.length() - 1);
            }
            if ("q".equals(key)) {
                try {
                    quality = Double.parseDouble(val);
                } catch (NumberFormatException e) {
                    quality = 1.0;
                }
            } else {
                parameters.put(key, val);
            }
        }

        return new ParsedMediaType(type, subtype, Collections.unmodifiableMap(parameters), quality);
    }

    /**
     * Split Accept header on commas, but respect quoted strings in parameter
     * values. Simple implementation: commas inside quoted strings are rare in
     * practice for DICOMweb, so we just split on comma.
     */
    private static String[] splitAcceptHeader(String header) {
        return header.split(",");
    }
}
