package com.firstelement.crm.domain;

/**
 * Messaging channels. Ported verbatim from the verified {@code "ChannelType"} enum in
 * {@code V20260814000000__baseline_verified_live_schema.sql}.
 *
 * <p>The wire value is the enum constant name, which is also what the database stores, so
 * a row written by the legacy NestJS runtime reads identically here.
 */
public enum ChannelType {
    MOCK,
    WHATSAPP,
    INSTAGRAM,
    MESSENGER,
    TELEGRAM;

    public static ChannelType fromValue(String value) {
        for (ChannelType channel : values()) {
            if (channel.name().equals(value)) {
                return channel;
            }
        }
        throw new IllegalArgumentException("Unknown channel: " + value);
    }
}