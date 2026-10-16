package com.krztechnologies.attend.hrms.service;

/**
 * The connection to KRZ HRMS, one of the planned extension points (P-07). Only {@link DummyHrmsClient} exists until the
 * HRMS API specification arrives; the HTTP client is written then.
 */
public interface HrmsClient {

    /**
     * Delivers one outbox entry. Its punch ID is stable, so a resend never creates a duplicate in HRMS.
     *
     * @throws DeliveryFailed when HRMS did not take it, with a short reason for the outbox
     */
    void deliver(Delivery delivery);

    /** What Settings → KRZ HRMS shows about the connection. */
    Connection connection();

    /** @param payload the R8 JSON: a punch, or a void with its reason */
    record Delivery(String code, String kind, String punchId, String payload) {}

    /** @param endpoint where punches go, null while not connected */
    record Connection(boolean connected, String label, String endpoint) {}

    /** HRMS did not take the entry; the message becomes the entry's last error. */
    final class DeliveryFailed extends RuntimeException {
        public DeliveryFailed(String message) {
            super(message);
        }
    }
}
