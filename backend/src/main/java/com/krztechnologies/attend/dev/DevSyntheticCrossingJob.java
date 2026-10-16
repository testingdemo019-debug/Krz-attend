package com.krztechnologies.attend.dev;

import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.employee.service.EmployeeDirectory;
import java.security.SecureRandom;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dev only ({@code DEV_SYNTHETIC_CROSSINGS=true}): every 20 s one crossing through the real pipeline: a tracked,
 * recognisable employee comes in if they are outside and goes out if they are inside; one in ten is an unknown face.
 */
@Component
@Profile("dev")
@ConditionalOnBooleanProperty(name = "krz.dev.synthetic-crossings")
public class DevSyntheticCrossingJob {

    private static final Logger LOG = LoggerFactory.getLogger(DevSyntheticCrossingJob.class);
    private static final int UNKNOWN_ONE_IN = 10;

    private final DevCrossings crossings;
    private final EmployeeDirectory employees;
    private final TimeService time;
    private final SecureRandom random = new SecureRandom();
    private final Set<UUID> inside = ConcurrentHashMap.newKeySet();

    public DevSyntheticCrossingJob(DevCrossings crossings, EmployeeDirectory employees, TimeService time) {
        this.crossings = crossings;
        this.employees = employees;
        this.time = time;
    }

    @Scheduled(fixedDelay = 20_000, initialDelay = 30_000)
    public void cross() {
        try {
            if (random.nextInt(UNKNOWN_ONE_IN) == 0) {
                crossings.inject(null, "IN", null);
                return;
            }
            List<UUID> ids = employees.attendanceEmployees(time.today());
            List<UUID> people = employees.attendanceProfiles(ids).values().stream()
                    .filter(p -> p.accountEnabled() && p.tracked() && p.recognisable())
                    .map(EmployeeDirectory.AttendanceProfile::id)
                    .toList();
            if (people.isEmpty()) {
                return;
            }
            UUID person = people.get(random.nextInt(people.size()));
            boolean in = inside.add(person);
            if (!in) {
                inside.remove(person);
            }
            crossings.inject(null, in ? "IN" : "OUT", person);
        } catch (RuntimeException e) {
            LOG.warn("Synthetic crossing skipped: {}", e.getMessage());
        }
    }
}
