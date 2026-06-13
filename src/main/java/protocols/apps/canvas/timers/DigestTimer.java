package protocols.apps.canvas.timers;

import pt.unl.fct.di.novasys.babel.generic.ProtoTimer;

/**
 * Fires periodically to make {@code CanvasApp} emit a convergence digest of its
 * local canvas to the telemetry log (see {@code CanvasApp.uponDigestTimer}). Set
 * up with {@code setupPeriodicTimer(...)} when {@code canvas.digest.interval > 0}.
 *
 * <p>Stateless, so {@link #clone()} returns {@code this} — the Babel convention.
 */
public class DigestTimer extends ProtoTimer {

    public static final short TIMER_ID = 301;

    public DigestTimer() {
        super(TIMER_ID);
    }

    @Override
    public ProtoTimer clone() {
        return this;
    }
}
