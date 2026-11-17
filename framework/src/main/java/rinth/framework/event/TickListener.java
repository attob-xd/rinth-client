package rinth.framework.event;

/** Receives {@link Events#TICK}. */
@FunctionalInterface
public interface TickListener {

    /** A client tick. */
    void onTick();
}
