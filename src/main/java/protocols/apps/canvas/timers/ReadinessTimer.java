package protocols.apps.canvas.timers;

import pt.unl.fct.di.novasys.babel.generic.ProtoTimer;

/**
 * Polls local overlay readiness before the paint workload starts (see
 * {@code CanvasApp.uponReadinessTimer}). After the aligned start-delay floor elapses,
 * this fires periodically and the node only begins broadcasting once its active view
 * is filled and neighbour churn has quiesced — or a timeout forces it to start anyway.
 * Broadcasting onto a half-formed overlay loses gossip information that only state
 * reconciliation (snapshot/anti-entropy) can later recover, which is what this gate
 * exists to prevent. Cancelled once the workload begins.
 *
 * <p>Stateless, so {@link #clone()} returns {@code this} — the Babel convention.
 */
public class ReadinessTimer extends ProtoTimer {

    public static final short TIMER_ID = 303;

    public ReadinessTimer() {
        super(TIMER_ID);
    }

    @Override
    public ProtoTimer clone() {
        return this;
    }
}
