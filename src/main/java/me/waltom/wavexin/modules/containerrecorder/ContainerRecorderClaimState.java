package me.waltom.wavexin.modules.containerrecorder;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Tracks scan-owned activation without taking ownership away from a manual enable. */
final class ContainerRecorderClaimState {
    private final Set<Object> requesters = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean automaticallyEnabled;

    boolean request(Object requester, boolean active) {
        requesters.add(requester);
        if (active) return false;
        automaticallyEnabled = true;
        return true;
    }

    boolean release(Object requester, boolean active) {
        requesters.remove(requester);
        if (!requesters.isEmpty() || !automaticallyEnabled || !active) return false;
        automaticallyEnabled = false;
        return true;
    }

    void manuallyActivated() {
        automaticallyEnabled = false;
    }

    void deactivated() {
        requesters.clear();
        automaticallyEnabled = false;
    }
}
