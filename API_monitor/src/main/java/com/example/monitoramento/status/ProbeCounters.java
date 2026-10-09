package com.example.monitoramento.status;

public record ProbeCounters(long tests, long successes, long failures, long executionErrors,
                            long confirmedFalls, String reportingMonth, long monthlyTests,
                            long monthlyFailures, long monthlyExecutionErrors) {
}
