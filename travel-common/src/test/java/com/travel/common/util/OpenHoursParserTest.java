package com.travel.common.util;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M8-3（M9-2 上移）：OpenHoursParser 宽松解析单测（6+ 格式 + 非法容错）。
 */
class OpenHoursParserTest {

    @Test
    void standardRange_parsed() {
        Optional<OpenHoursParser.Hours> h = OpenHoursParser.parse("09:00-17:00");
        assertThat(h).contains(new OpenHoursParser.Hours(LocalTime.of(9, 0), LocalTime.of(17, 0)));
    }

    @Test
    void halfHourRange_parsed() {
        Optional<OpenHoursParser.Hours> h = OpenHoursParser.parse("08:30-17:30");
        assertThat(h).contains(new OpenHoursParser.Hours(LocalTime.of(8, 30), LocalTime.of(17, 30)));
    }

    @Test
    void weekdayPrefix_ignored() {
        Optional<OpenHoursParser.Hours> h = OpenHoursParser.parse("周一至周日 08:00-18:00");
        assertThat(h).contains(new OpenHoursParser.Hours(LocalTime.of(8, 0), LocalTime.of(18, 0)));
    }

    @Test
    void allDay_parsedAsFullDay() {
        assertThat(OpenHoursParser.parse("全天开放"))
                .contains(new OpenHoursParser.Hours(LocalTime.MIN, LocalTime.of(23, 59)));
        assertThat(OpenHoursParser.parse("全天"))
                .contains(new OpenHoursParser.Hours(LocalTime.MIN, LocalTime.of(23, 59)));
    }

    @Test
    void multiSeasonRange_takesMaxInterval() {
        Optional<OpenHoursParser.Hours> h = OpenHoursParser.parse("旺季08:00-18:00,淡季09:00-17:00");
        assertThat(h).contains(new OpenHoursParser.Hours(LocalTime.of(8, 0), LocalTime.of(18, 0)));
    }

    @Test
    void singleDigitHour_parsed() {
        Optional<OpenHoursParser.Hours> h = OpenHoursParser.parse("8:00-17:00");
        assertThat(h).contains(new OpenHoursParser.Hours(LocalTime.of(8, 0), LocalTime.of(17, 0)));
    }

    @Test
    void invalidInput_returnsEmpty() {
        assertThat(OpenHoursParser.parse("待定")).isEmpty();
        assertThat(OpenHoursParser.parse("")).isEmpty();
        assertThat(OpenHoursParser.parse(null)).isEmpty();
        assertThat(OpenHoursParser.parse("开放时间以现场为准")).isEmpty();
    }

    @Test
    void timeSlot_parsed() {
        assertThat(OpenHoursParser.parseSlot("09:00-12:00"))
                .contains(new OpenHoursParser.Hours(LocalTime.of(9, 0), LocalTime.of(12, 0)));
        assertThat(OpenHoursParser.parseSlot("非时间格式")).isEmpty();
    }
}
