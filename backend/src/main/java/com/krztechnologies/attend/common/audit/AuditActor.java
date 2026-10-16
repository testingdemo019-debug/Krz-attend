package com.krztechnologies.attend.common.audit;

import java.util.UUID;

/**
 * Who did something: a signed-in employee, or a system actor shown by name.
 *
 * @param employeeId null for system actors
 * @param label the name shown in the audit log
 */
public record AuditActor(UUID employeeId, String label) {

    public static final AuditActor SYSTEM = new AuditActor(null, "System");
    public static final AuditActor DIRECTORY_SYNC = new AuditActor(null, "Directory sync");

    public static AuditActor employee(UUID employeeId, String displayName) {
        return new AuditActor(employeeId, displayName);
    }
}
