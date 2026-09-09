package com.convertx2x.excel2md.conversion;

import java.net.URI;
import java.util.Locale;

public final class Markdown {
    private Markdown() { }
    public static String escape(String input) {
        StringBuilder out = new StringBuilder();
        for (char c : input.replace("\r\n", "\n").replace('\r', '\n').toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '\\', '`', '*', '_', '[', ']', '|', '#', '!', '{', '}', '~', '+', '-' -> out.append('\\').append(c);
                case '\0' -> { }
                default -> out.append(c);
            }
        }
        // A leading number plus period must not become a Markdown list.
        return out.toString().replaceAll("(?m)^(\\d+)\\.(?=\\s|$)", "$1\\\\.");
    }
    public static String bold(String escaped) {
        if (escaped.isEmpty()) return escaped;
        StringBuilder out = new StringBuilder();
        String[] lines = escaped.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            String line = lines[i];
            if (line.isEmpty()) continue;
            if (Character.isLetterOrDigit(line.codePointAt(0)) && Character.isLetterOrDigit(line.codePointBefore(line.length())))
                out.append("**").append(line).append("**");
            else out.append("<strong>").append(line).append("</strong>");
        }
        return out.toString();
    }
    public static boolean safeLink(String address) {
        if (address == null || address.isBlank() || address.chars().anyMatch(c -> c < 32 || c == 127 || c == '\\')) return false;
        try {
            URI uri = URI.create(address.replace(" ", "%20"));
            String scheme = uri.getScheme();
            if (scheme == null) return false;
            return switch (scheme.toLowerCase(Locale.ROOT)) {
                case "http", "https" -> uri.getRawAuthority() != null && !uri.getRawAuthority().isBlank() && uri.getRawUserInfo() == null;
                case "mailto" -> !uri.getRawSchemeSpecificPart().isBlank();
                default -> false;
            };
        } catch (IllegalArgumentException e) { return false; }
    }
    public static String link(String escapedLabel, String address) {
        if (!safeLink(address)) return escapedLabel;
        // Preserve existing percent encoding, while protecting Markdown delimiters.
        String destination = address.replace(" ", "%20").replace("(", "%28").replace(")", "%29")
                .replace("<", "%3C").replace(">", "%3E").replace("\"", "%22").replace("|", "%7C");
        return "[" + escapedLabel + "](" + destination + ")";
    }
}
