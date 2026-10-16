package com.krztechnologies.attend.common.error;

import com.krztechnologies.attend.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Writes the error envelope from servlet filters (before a controller exists): 401, 403, 429, session expiry. */
@Component
public class ErrorWriter {

    private final JsonMapper json;

    public ErrorWriter(JsonMapper json) {
        this.json = json;
    }

    public void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        json.writeValue(
                response.getOutputStream(),
                new ErrorResponse(code, message, List.of(), TraceIdFilter.currentTraceId()));
    }
}
