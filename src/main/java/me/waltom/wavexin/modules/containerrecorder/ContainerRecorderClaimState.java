package me.waltom.wavexin.modules.containerrecorder;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Linked scans take ownership even when the recorder was already enabled manually. */
final class ContainerRecorderClaimState {
    private final Set<Object> requesters = Collections.newSetFromMap(new IdentityHashMap<>());

    boolean request(Object requester, boolean active) {
        requesters.add(requester);
        return !active;
    }

    boolean release(Object requester, boolean active) {
        return requesters.remove(requester) && requesters.isEmpty() && active;
    }
}
