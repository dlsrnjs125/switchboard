package io.github.dlsrnjs125.switchboard.distribution.grpc;

final class BackpressureEvidenceGate {
    private BackpressureEvidenceGate() {
    }

    static void verify(
            Phase9BackpressureEvidenceTest.ScheduledRun run,
            long maximumScheduleLagP99Micros,
            double minimumUpdateRatePerSecond) {
        long scheduleLagP99 = run.scheduleLagMicros().get("p99");
        if (scheduleLagP99 > maximumScheduleLagP99Micros) {
            throw new IllegalStateException(
                    "schedule lag p99 exceeded experiment target: " + scheduleLagP99);
        }
        if (run.updatesPerSecond() < minimumUpdateRatePerSecond) {
            throw new IllegalStateException(
                    "update rate fell below experiment target: " + run.updatesPerSecond());
        }
    }
}
