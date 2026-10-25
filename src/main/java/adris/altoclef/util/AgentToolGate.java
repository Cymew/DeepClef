package adris.altoclef.util;

import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;

/** Client-thread callback ownership; deferred actions retain their real completion callback. */
public final class AgentToolGate {
    public enum Completion { ACTIVE, DEFERRED, STALE }
    public record Ticket(long sequence, Object batch, int index) { }
    private long sequence;
    private Ticket active;
    private final Set<Ticket> deferred = Collections.newSetFromMap(new IdentityHashMap<>());

    public Ticket open(Object batch, int index) {
        return active = new Ticket(++sequence, batch, index);
    }
    public boolean isActive(Ticket ticket) { return ticket != null && active == ticket; }
    public boolean defer(Ticket ticket) {
        if (!isActive(ticket)) return false;
        deferred.add(ticket); active = null; return true;
    }
    public Completion complete(Ticket ticket) {
        if (isActive(ticket)) { active = null; return Completion.ACTIVE; }
        return deferred.remove(ticket) ? Completion.DEFERRED : Completion.STALE;
    }
    public boolean hasDeferred() { return !deferred.isEmpty(); }
    public void cancelDeferred() { deferred.clear(); }
    public void clear() { active = null; deferred.clear(); }
}
