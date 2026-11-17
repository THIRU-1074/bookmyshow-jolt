package dev.thiru.bms.web;

import dev.thiru.bms.domain.Role;
import in.jolt.Request;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** The signed-in caller, read from the verified JWT claims the middleware stored on the request. */
record Principal(long userId, Set<Role> roles) {

    boolean isAdmin() {
        return roles.contains(Role.ADMIN);
    }

    boolean isUser() {
        return roles.contains(Role.AUDIENCE) || isAdmin();
    }

    static Principal of(Request req) {
        Object sub = req.attr("user");
        if (sub == null) {
            throw ApiException.unauthenticated("Authentication required");
        }
        long id;
        try {
            id = Long.parseLong(sub.toString());
        } catch (NumberFormatException e) {
            throw ApiException.unauthenticated("Invalid token subject");
        }
        Set<Role> roles = EnumSet.noneOf(Role.class);
        if (req.attr("claims") instanceof Map<?, ?> claims) {
            Object raw = claims.get("roles");
            if (raw instanceof Collection<?> list) {
                list.forEach(r -> addRole(roles, r));
            } else if (raw != null) {
                addRole(roles, raw);
            }
        }
        return new Principal(id, Set.copyOf(roles));
    }

    private static void addRole(Set<Role> roles, Object raw) {
        // Access tokens issued before V4 still say USER for up to 15 minutes after the deploy.
        if ("USER".equals(raw.toString())) {
            roles.add(Role.AUDIENCE);
            return;
        }
        try {
            roles.add(Role.valueOf(raw.toString()));
        } catch (IllegalArgumentException ignored) {
            // An unknown role grants nothing.
        }
    }
}
