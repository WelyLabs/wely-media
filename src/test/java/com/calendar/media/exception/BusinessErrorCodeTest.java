package com.calendar.media.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the code convention, so a new constant cannot silently break what callers branch on. */
class BusinessErrorCodeTest {

    private static List<String> codes() {
        return Arrays.stream(BusinessErrorCode.values()).map(BusinessErrorCode::getCode).toList();
    }

    @Test
    void values_shouldFollowTheBusinessCodeConvention_forEveryCode() {
        assertThat(codes()).allMatch(code -> code.matches("^MED-(BUS|VAL|REQ)-\\d{3}$"));
    }

    @Test
    void values_shouldBeUnique_acrossTheEnum() {
        assertThat(codes()).doesNotHaveDuplicates();
    }

    @Test
    void values_shouldAnswerA4xxStatus_forEveryCode() {
        assertThat(BusinessErrorCode.values()).allSatisfy(error ->
                assertThat(error.getStatus()).as(error.getCode()).isBetween(400, 499));
    }

    @Test
    @DisplayName("no constant reuses a code the error handler writes on its own")
    void values_shouldNotReuseACode_writtenByTheErrorHandler() {
        assertThat(codes()).doesNotContain(GlobalErrorHandler.VALIDATION_CODE, GlobalErrorHandler.REQUEST_CODE,
                GlobalErrorHandler.UNEXPECTED_CODE);
    }

    @Test
    void values_shouldNotOverlap_withTechnicalCodes() {
        List<String> technical = Arrays.stream(TechnicalErrorCode.values()).map(TechnicalErrorCode::getCode).toList();

        assertThat(codes()).doesNotContainAnyElementsOf(technical);
    }
}
