package com.example.yapit.model;

public final class EnrollmentResult {
    public final String enrollmentId;
    public EnrollmentResult(String enrollmentId) { this.enrollmentId = enrollmentId; }
    public static EnrollmentResult notRequired() { return new EnrollmentResult(""); }
}
