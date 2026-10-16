package com.krztechnologies.attend.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/**
 * Deletes every session of a user from Redis (Spring Session's principal index). The principal name is the user's
 * Entra object ID ({@code oid}), the same attribute the sign-in uses.
 */
@Component
public class SessionRevoker {

    private static final Logger LOG = LoggerFactory.getLogger(SessionRevoker.class);

    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public SessionRevoker(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    /** Returns the number of sessions deleted. */
    public int revokeAll(String principalName) {
        var found = sessions.findByPrincipalName(principalName);
        found.keySet().forEach(sessions::deleteById);
        if (!found.isEmpty()) {
            LOG.info("Revoked {} session(s)", found.size());
        }
        return found.size();
    }
}
