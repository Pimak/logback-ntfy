package io.github.pimak.logbackntfy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Verifies {@link AlertMessages#digestTitle} and {@link AlertMessages#digestBody} produce the
 * expected digest formats and remain credential-safe.
 */
class AlertMessagesDigestTest {

  @Test
  void digestTitle_withAppName_formatsEmDashCount() {
    String result = AlertMessages.digestTitle("MyApp", 47);

    assertThat(result).isEqualTo("MyApp — 47 errors suppressed");
  }

  @Test
  void digestTitle_withNullBase_treatsAsEmptyStringNoNpe() {
    String result = AlertMessages.digestTitle(null, 47);

    assertThat(result).isEqualTo(" — 47 errors suppressed");
  }

  @Test
  void digestBody_startsWithCountAndWindowDescription() {
    Map<String, Integer> tally = new LinkedHashMap<>();
    tally.put("com.foo.Bar", 12);

    String result = AlertMessages.digestBody(12, tally, "3 minutes");

    assertThat(result).startsWith("12 errors suppressed in the last 3 minutes");
  }

  @Test
  void digestBody_threeLoggers_noOverflowMarker() {
    Map<String, Integer> tally = new LinkedHashMap<>();
    tally.put("com.foo.Bar", 5);
    tally.put("com.foo.Baz", 3);
    tally.put("com.foo.Qux", 1);

    String result = AlertMessages.digestBody(9, tally, "3 minutes");

    assertThat(result).doesNotContain("others");
    assertThat(result).contains("5x com.foo.Bar");
    assertThat(result).contains("3x com.foo.Baz");
    assertThat(result).contains("1x com.foo.Qux");
  }

  @Test
  void digestBody_eightLoggers_topFivePlusOverflowMarker() {
    Map<String, Integer> tally = new LinkedHashMap<>();
    tally.put("logger1", 8);
    tally.put("logger2", 7);
    tally.put("logger3", 6);
    tally.put("logger4", 5);
    tally.put("logger5", 4);
    tally.put("logger6", 3);
    tally.put("logger7", 2);
    tally.put("logger8", 1);

    String result = AlertMessages.digestBody(36, tally, "3 minutes");

    long loggerLineCount =
        java.util.Arrays.stream(result.split("\n"))
            .filter(line -> line.contains("x logger"))
            .count();
    assertThat(loggerLineCount).isEqualTo(5);
    assertThat(result).contains("+3 others");
    assertThat(result).contains("8x logger1");
    assertThat(result).contains("7x logger2");
    assertThat(result).doesNotContain("1x logger8");
  }

  @Test
  void digestBody_neverContainsCredentialFields() {
    Map<String, Integer> tally = new LinkedHashMap<>();
    tally.put("com.foo.Bar", 1);

    String title = AlertMessages.digestTitle("MyApp", 1);
    String body = AlertMessages.digestBody(1, tally, "3 minutes");

    assertThat(title.toLowerCase()).doesNotContain("token").doesNotContain("password");
    assertThat(body.toLowerCase())
        .doesNotContain("token")
        .doesNotContain("username")
        .doesNotContain("password");
  }
}
