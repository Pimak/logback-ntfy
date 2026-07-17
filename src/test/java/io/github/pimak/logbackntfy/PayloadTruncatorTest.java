package io.github.pimak.logbackntfy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Verifies {@link PayloadTruncator} keeps the assembled ntfy body under the 4096-byte budget,
 * sacrificing trailing lines (stack frames, appended last) before earlier lines.
 */
class PayloadTruncatorTest {

  @Test
  void bodyUnderBudget_isReturnedUnchanged() {
    String body = "Message: boom\nLogger: com.example.Foo\nTime: 2026-07-14T00:00:00Z";

    String result = PayloadTruncator.truncate(body, PayloadTruncator.NTFY_MAX_BYTES);

    assertThat(result).isEqualTo(body);
  }

  @Test
  void bodyOverBudget_isTruncatedUnderByteLimit() {
    StringBuilder sb = new StringBuilder();
    sb.append("Message: boom\n").append("Logger: com.example.Foo\n");
    for (int i = 0; i < 500; i++) {
      sb.append("  at com.example.Foo.method")
          .append(i)
          .append("(Foo.java:")
          .append(i)
          .append(")\n");
    }
    String body = sb.toString();
    assertThat(body.getBytes(StandardCharsets.UTF_8).length)
        .isGreaterThan(PayloadTruncator.NTFY_MAX_BYTES);

    String result = PayloadTruncator.truncate(body, PayloadTruncator.NTFY_MAX_BYTES);

    assertThat(result.getBytes(StandardCharsets.UTF_8).length)
        .isLessThanOrEqualTo(PayloadTruncator.NTFY_MAX_BYTES);
  }

  @Test
  void bodyOverBudget_sacrificesTrailingFrameLinesFirst() {
    String earlyLine = "Message: boom";
    StringBuilder sb = new StringBuilder();
    sb.append(earlyLine).append('\n');
    for (int i = 0; i < 500; i++) {
      sb.append("  at com.example.Foo.method")
          .append(i)
          .append("(Foo.java:")
          .append(i)
          .append(")\n");
    }
    String body = sb.toString();

    String result = PayloadTruncator.truncate(body, PayloadTruncator.NTFY_MAX_BYTES);

    assertThat(result).startsWith(earlyLine);
    assertThat(result).doesNotContain("method499");
  }

  @Test
  void multiByteUtf8Content_neverExceedsByteBudgetOrSplitsCharacter() {
    StringBuilder sb = new StringBuilder();
    sb.append("Message: erreur avec des caractères accentués éèêàç 😀\n");
    for (int i = 0; i < 500; i++) {
      sb.append("  at com.exemple.Événement.méthode")
          .append(i)
          .append("(Événement.java:")
          .append(i)
          .append(")\n");
    }
    String body = sb.toString();
    assertThat(body.getBytes(StandardCharsets.UTF_8).length)
        .isGreaterThan(PayloadTruncator.NTFY_MAX_BYTES);

    String result = PayloadTruncator.truncate(body, PayloadTruncator.NTFY_MAX_BYTES);

    byte[] resultBytes = result.getBytes(StandardCharsets.UTF_8);
    assertThat(resultBytes.length).isLessThanOrEqualTo(PayloadTruncator.NTFY_MAX_BYTES);
    // Re-decoding must round-trip without replacement characters, proving no multi-byte
    // character was split mid-sequence.
    String redecoded = new String(resultBytes, StandardCharsets.UTF_8);
    assertThat(redecoded).isEqualTo(result);
    assertThat(redecoded).doesNotContain("�");
  }

  // A single line over the budget used to yield an EMPTY body — losing the message, logger,
  // cause chain, and timestamp entirely. It must be hard-truncated instead.
  @Test
  void oversizedFirstLine_isHardTruncatedNotEmptied() {
    String line = "Message: " + "x".repeat(6000);
    assertThat(line).doesNotContain("\n");
    assertThat(line.getBytes(StandardCharsets.UTF_8).length)
        .isGreaterThan(PayloadTruncator.NTFY_MAX_BYTES);

    String result = PayloadTruncator.truncate(line, PayloadTruncator.NTFY_MAX_BYTES);

    assertThat(result).isNotEmpty();
    assertThat(result).startsWith("Message: ");
    assertThat(result.getBytes(StandardCharsets.UTF_8).length)
        .isLessThanOrEqualTo(PayloadTruncator.NTFY_MAX_BYTES);
    assertThat(line).startsWith(result);
  }

  @Test
  void oversizedFirstLineWithMultiByteContent_isCutOnCharacterBoundary() {
    String line = "Message: " + "é😀à".repeat(2000);
    assertThat(line.getBytes(StandardCharsets.UTF_8).length)
        .isGreaterThan(PayloadTruncator.NTFY_MAX_BYTES);

    String result = PayloadTruncator.truncate(line, PayloadTruncator.NTFY_MAX_BYTES);

    assertThat(result).isNotEmpty();
    byte[] resultBytes = result.getBytes(StandardCharsets.UTF_8);
    assertThat(resultBytes.length).isLessThanOrEqualTo(PayloadTruncator.NTFY_MAX_BYTES);
    // Round-trip without replacement characters proves no multi-byte character was split.
    String redecoded = new String(resultBytes, StandardCharsets.UTF_8);
    assertThat(redecoded).isEqualTo(result);
    assertThat(redecoded).doesNotContain("�");
    assertThat(line).startsWith(result);
  }

  // Regression: the empty/null fast paths must survive the hard-truncation fallback.
  @Test
  void emptyAndNullBodies_stillReturnEmptyString() {
    assertThat(PayloadTruncator.truncate("", PayloadTruncator.NTFY_MAX_BYTES)).isEmpty();
    assertThat(PayloadTruncator.truncate(null, PayloadTruncator.NTFY_MAX_BYTES)).isEmpty();
  }
}
