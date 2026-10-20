package com.calendar.media.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the code convention, so a new constant cannot silently break what callers branch on. */
class TechnicalErrorCodeTest {

    private static List<String> codes() {
        return Arrays.stream(TechnicalErrorCode.values()).map(TechnicalErrorCode::getCode).toList();
    }

    @Test
    void values_shouldFollowTheTechnicalCodeConvention_forEveryCode() {
        assertThat(codes()).allMatch(code -> code.matches("^MED-TEC-\\d{3}$"));
    }

    @Test
    void values_shouldBeUnique_acrossTheEnum() {
        assertThat(codes()).doesNotHaveDuplicates();
    }

    @Test
    void values_shouldAnswerA5xxStatus_forEveryCode() {
        assertThat(TechnicalErrorCode.values()).allSatisfy(error ->
                assertThat(error.getHttpStatus().is5xxServerError()).as(error.getCode()).isTrue());
    }

    @Test
    @DisplayName("no constant reuses a code the error handler writes on its own")
    void values_shouldNotReuseACode_writtenByTheErrorHandler() {
        assertThat(codes()).doesNotContain(GlobalErrorHandler.VALIDATION_CODE, GlobalErrorHandler.REQUEST_CODE,
                GlobalErrorHandler.UNEXPECTED_CODE);
    }

    @Test
    void values_shouldNotOverlap_withBusinessCodes() {
        List<String> business = Arrays.stream(BusinessErrorCode.values()).map(BusinessErrorCode::getCode).toList();

        assertThat(codes()).doesNotContainAnyElementsOf(business);
    }
}
