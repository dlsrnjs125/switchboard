package io.github.dlsrnjs125.switchboard.observability;

public final class ObservationNames {
    public static final String CONTROL_PUBLISH = "switchboard.control.publish";
    public static final String OUTBOX_DELIVERY = "switchboard.outbox.delivery";
    public static final String DISTRIBUTION_RECONCILE = "switchboard.distribution.reconcile";
    public static final String DISTRIBUTION_GRPC_EVENT = "switchboard.distribution.grpc.event";
    public static final String SDK_SNAPSHOT_APPLY = "switchboard.sdk.snapshot.apply";
    public static final String SDK_EVALUATION = "switchboard.sdk.evaluation";

    private ObservationNames() {
    }
}
