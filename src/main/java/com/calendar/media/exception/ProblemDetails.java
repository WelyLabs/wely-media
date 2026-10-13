package com.calendar.media.exception;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.time.Instant;

/** The one RFC 7807 shape, shared by the controller advice and the security entry point. */
public final class ProblemDetails {

    /** Namespace for the {@code type} URI. Not dereferenced; it identifies, it does not serve. */
    static final String PROBLEM_TYPE_BASE = "https://welylabs.app/problems/";

    private ProblemDetails() {
    }

    public static ProblemDetail of(MediaErrorCode error, String path) {
        return of(HttpStatusCode.valueOf(error.getStatus()), error.getCode(), error.getTitle(), error.getDetail(), path);
    }

    public static ProblemDetail of(HttpStatusCode status, String code, String title, String detail, String path) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(PROBLEM_TYPE_BASE + code.toLowerCase()));
        problem.setTitle(title);
        problem.setInstance(URI.create(path));
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }
}
