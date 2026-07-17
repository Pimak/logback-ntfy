package io.github.pimak.logbackntfy;

/**
 * Immutable {@code {title, body}} pair assembled by {@link PayloadBuilder} from an {@code
 * ILoggingEvent}. {@code body} is already truncated to {@link PayloadTruncator#NTFY_MAX_BYTES}
 * UTF-8 bytes by the time it reaches this record.
 */
record AlertPayload(String title, String body) {}
