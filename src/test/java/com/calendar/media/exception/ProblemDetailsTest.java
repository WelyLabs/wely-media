package com.calendar.media.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemDetailsTest {

    @Test
    void of_shouldBuildTheProblemFromAnErrorCode() {
        ProblemDetail problem = ProblemDetails.of(MediaErrorCode.CLIENT_UNKNOWN, "/internal/media");

        assertThat(problem.getStatus()).isEqualTo(401);
        assertThat(problem.getTitle()).isEqualTo(MediaErrorCode.CLIENT_UNKNOWN.getTitle());
        assertThat(problem.getDetail()).isEqualTo(MediaErrorCode.CLIENT_UNKNOWN.getDetail());
        assertThat(problem.getType()).isEqualTo(URI.create("https://welylabs.app/problems/med-req-001"));
        assertThat(problem.getInstance()).isEqualTo(URI.create("/internal/media"));
        assertThat(problem.getProperties()).containsEntry("code", "MED-REQ-001").containsKey("timestamp");
    }

    @Test
    void of_shouldBuildTheProblemFromExplicitFields() {
        ProblemDetail problem = ProblemDetails.of(HttpStatus.BAD_GATEWAY, "MED-TEC-001", "t", "d", "/x");

        assertThat(problem.getStatus()).isEqualTo(502);
        assertThat(problem.getProperties()).containsEntry("code", "MED-TEC-001");
    }
}
