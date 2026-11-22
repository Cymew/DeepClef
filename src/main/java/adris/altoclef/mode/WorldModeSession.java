package adris.altoclef.mode;

import java.io.IOException;

/** Client-thread state for one connection. Dimension and local player objects can change independently. */
public final class WorldModeSession {
    private Object connection;
    private WorldModeStore store;
    private WorldMode mode;
    private boolean prompted;

    public void enter(Object current) {
        if (connection == current) return;
        connection = current;
        store = null;
        mode = null;
        prompted = false;
    }

    public boolean needsIdentity() { return connection != null && store == null && !prompted; }
    public WorldModeStore store() { return store; }
    public void rejectIdentity() { prompted = true; }

    public void load(WorldModeStore identity) throws IOException {
        store = identity;
        try {
            mode = store.read();
        } catch (IOException failure) {
            prompted = true;
            throw failure;
        }
    }

    public boolean takePrompt() {
        if (store == null || mode != null || prompted) return false;
        prompted = true;
        return true;
    }

    public WorldMode select(Object owner, WorldMode choice) throws IOException {
        if (owner == null || owner != connection || store == null || !prompted) return null;
        if (mode == null) mode = store.bind(choice);
        return mode;
    }

    public WorldMode current(Object owner) { return owner == connection ? mode : null; }
}
