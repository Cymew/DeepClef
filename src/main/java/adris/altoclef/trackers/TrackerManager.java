package adris.altoclef.trackers;

import adris.altoclef.AltoClef;

import java.util.ArrayList;

public class TrackerManager {

    private final ArrayList<Tracker> _trackers = new ArrayList<>();

    private final AltoClef _mod;

    private net.minecraft.client.multiplayer.ClientPacketListener _connection;

    public TrackerManager(AltoClef mod) {
        _mod = mod;
    }

    public void tick() {
        var connection = AltoClef.inGame() ? _mod.getPlayer().connection : null;
        if (_connection != null && connection != _connection) {
            // Reset when we leave our world
            for (Tracker tracker : _trackers) {
                tracker.reset();
            }
            // This is a a spaghetti. Fix at some point.
            _mod.getChunkTracker().reset(_mod);
            _mod.getMiscBlockTracker().reset();
        }
        _connection = connection;

        for (Tracker tracker : _trackers) {
            tracker.setDirty();
        }
    }

    public void addTracker(Tracker tracker) {
        tracker._mod = _mod;
        _trackers.add(tracker);
    }
}
