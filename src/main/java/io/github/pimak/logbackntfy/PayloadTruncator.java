package io.github.pimak.logbackntfy;

import java.nio.charset.StandardCharsets;

/**
 * Truncates an assembled ntfy alert body to a byte budget (ntfy's hard limit is 4096
 * bytes), sacrificing whole trailing lines first. Truncation is measured via {@code
 * getBytes(UTF_8).length}, never {@code String.length()}, so multi-byte UTF-8 content (accents,
 * emoji) is never split mid-character and the result never exceeds the byte budget.
 */
final class PayloadTruncator {

  /** ntfy's hard body-size limit in bytes. */
  static final int NTFY_MAX_BYTES = 4096;

  private PayloadTruncator() {}

  /**
   * Returns {@code body} unchanged if it already fits within {@code maxBytes} UTF-8 bytes.
   * Otherwise rebuilds the body line-by-line (split on {@code '\n'}), keeping whole lines from the
   * start until the next line would exceed the budget — dropping trailing lines (stack frames,
   * appended last during payload assembly) first. When not even the FIRST line fits (a single log
   * message over the budget), that line is hard-truncated to a byte-safe prefix instead of
   * returning an empty body — losing the tail of one line beats losing the message, logger, cause
   * chain, and timestamp entirely.
   */
  static String truncate(String body, int maxBytes) {
    if (body == null) {
      return "";
    }
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= maxBytes) {
      return body;
    }
    String[] lines = body.split("\n", -1);
    StringBuilder sb = new StringBuilder();
    for (String line : lines) {
      String candidate = sb + line + "\n";
      if (candidate.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
        break;
      }
      sb.append(line).append('\n');
    }
    if (sb.length() == 0 && lines.length > 0) {
      // No whole line fit: the first line alone exceeds the budget. Hard-truncate it
      // intra-line on a UTF-8 character boundary rather than emptying the body.
      return truncateToBytes(lines[0], maxBytes);
    }
    // Strip the single trailing newline added by the loop above so callers get a clean body.
    if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
      sb.setLength(sb.length() - 1);
    }
    return sb.toString();
  }

  /**
   * Byte-safe intra-line truncation: cuts {@code line} to at most {@code maxBytes} UTF-8 bytes,
   * walking the cut point back past any UTF-8 continuation bytes ({@code 10xxxxxx}) so a multi-byte
   * character is never split mid-sequence — the result always re-encodes to exactly the bytes it
   * was decoded from (no replacement character).
   */
  private static String truncateToBytes(String line, int maxBytes) {
    byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= maxBytes) {
      return line;
    }
    int cut = Math.max(maxBytes, 0);
    // bytes[cut] is the first EXCLUDED byte; back up until it is a character lead byte, so the
    // character spanning the cut point is dropped entirely instead of split.
    while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) {
      cut--;
    }
    return new String(bytes, 0, cut, StandardCharsets.UTF_8);
  }
}
