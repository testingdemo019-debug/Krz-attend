package com.krztechnologies.attend.hrms.service;

import org.springframework.stereotype.Component;

/** KRZ HRMS is not connected yet (P-07): nothing is ever sent, and the status says so. */
@Component
public class DummyHrmsClient implements HrmsClient {

    @Override
    public void deliver(Delivery delivery) {
        throw new DeliveryFailed("KRZ HRMS is not connected");
    }

    @Override
    public Connection connection() {
        return new Connection(false, "Not connected — dummy", null);
    }
}
