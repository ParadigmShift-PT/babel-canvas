package protocols.apps.canvas.timers;

import pt.unl.fct.di.novasys.babel.generic.ProtoTimer;

/**
 * Polls the shared control file in orchestrator-coordinated workload mode (see
 * {@code CanvasApp.uponControlTimer}). The experiment script lets the whole system join
 * and settle, then writes {@code RUN} to begin the paint workload on every node at once,
 * and later {@code STOP} to cease — so painting starts only once ALL nodes are present
 * (a node's own view being stable is not enough; late joiners would miss its early ops).
 * Set up with {@code setupPeriodicTimer(...)} when {@code canvas.workload.controlFile} is
 * configured; otherwise the node uses the legacy single start-delay timer.
 *
 * <p>Stateless, so {@link #clone()} returns {@code this} — the Babel convention.
 */
public class ControlTimer extends ProtoTimer {

    public static final short TIMER_ID = 303;

    public ControlTimer() {
        super(TIMER_ID);
    }

    @Override
    public ProtoTimer clone() {
        return this;
    }
}
