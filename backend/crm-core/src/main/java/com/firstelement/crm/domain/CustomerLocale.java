package com.firstelement.crm.domain;

/** Customer locale. Ported verbatim from the verified {@code "Locale"} enum. */
public enum CustomerLocale {
    AR("ar"),
    EN("en");

    private final String value;

    CustomerLocale(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static CustomerLocale fromValue(String value) {
        for (CustomerLocale locale : values()) {
            if (locale.value.equals(value)) {
                return locale;
            }
        }
        throw new IllegalArgumentException("Unknown locale: " + value);
    }
}