package protocols.apps.canvas.timers;

import pt.unl.fct.di.novasys.babel.generic.ProtoTimer;

/**
 * Fires at the configured paint rate to make {@code CanvasApp} generate one
 * random paint operation in headless workload mode (see
 * {@code CanvasApp.uponWorkloadTimer}). Set up with {@code setupPeriodicTimer(...)}
 * when {@code canvas.workload.enabled=true}; this is how the experiments harness
 * drives load without the web UI.
 *
 * <p>Stateless, so {@link #clone()} returns {@code this} — the Babel convention.
 */
public class WorkloadTimer extends ProtoTimer {

    public static final short TIMER_ID = 302;

    public WorkloadTimer() {
        super(TIMER_ID);
    }

    @Override
    public ProtoTimer clone() {
        return this;
    }
}
