package com.krztechnologies.attend.common.realtime;

import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.security.SignedIn;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** The browser's single event stream (SSE). Events are filtered by the user's permissions. */
@RestController
@RequestMapping("/api/v1/realtime")
public class RealtimeController {

    private final SseHub hub;
    private final CurrentUser currentUser;

    public RealtimeController(SseHub hub, CurrentUser currentUser) {
        this.hub = hub;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Server-sent events: notification.created, access.requests, system.status, …")
    @SignedIn
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return hub.subscribe(currentUser.employeeId());
    }
}
