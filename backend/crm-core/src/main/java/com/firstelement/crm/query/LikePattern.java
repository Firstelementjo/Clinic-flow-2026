package com.firstelement.crm.query;

/**
 * LIKE/ILIKE pattern building for user-supplied search text.
 *
 * <h2>Why escaping is not optional</h2>
 *
 * <p>Prisma's {@code contains, mode: 'insensitive'} escapes {@code %} and {@code _} before
 * emitting {@code ILIKE}. Without the same escaping here, a customer whose name contains
 * {@code %} turns a search into "match everything", and a search containing {@code _}
 * matches any single character. The legacy behaviour being preserved here is "search for
 * this literal substring", so the wildcards are escaped and an explicit
 * {@code escape '\'} clause is emitted with every pattern.
 */
public final class LikePattern {

    private LikePattern() {
    }

    /** {@code %} + the literal, escaped, + {@code %}. */
    public static String contains(String literal) {
        return "%" + escape(literal) + "%";
    }

    /** The literal with {@code \}, {@code %} and {@code _} escaped. */
    public static String escape(String literal) {
        if (literal == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(literal.length() + 8);
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (c == '\\' || c == '%' || c == '_') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }
}