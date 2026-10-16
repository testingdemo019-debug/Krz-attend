package com.krztechnologies.attend.system.service;

import com.krztechnologies.attend.common.error.ErrorResponse;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.settings.GeneralSettings;
import com.krztechnologies.attend.common.settings.SettingsArea;
import com.krztechnologies.attend.common.settings.SettingsService;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settings → General: organisation name, time zone, date and time format, the Employee ID pattern (it must compile,
 * since employment editing validates against it) and the undo window. The logo has its own endpoints
 * ({@link LogoService}). Saving is audited by {@link SettingsService}.
 */
@Service
public class GeneralSettingsService {

    /**
     * Day, month and year tokens (D, DD, M…MMMM, YY, YYYY) with separators, as the frontend formats dates; each part
     * appears.
     */
    private static final Pattern DATE_FORMAT =
            Pattern.compile("^(?=.*D)(?=.*M)(?=.*Y)(?:D{1,2}|M{1,4}|Y{4}|Y{2}|[ ./,-])+$");

    private final SettingsService settings;
    private final LogoService logo;

    public GeneralSettingsService(SettingsService settings, LogoService logo) {
        this.settings = settings;
        this.logo = logo;
    }

    /**
     * @param logoUrl null while no logo is uploaded
     */
    public record GeneralView(
            GeneralSettings settings, int version, @Nullable String logoUrl) {}

    @Transactional(readOnly = true)
    public GeneralView view() {
        return view(settings.get(SettingsArea.GENERAL));
    }

    /** Saves the whole page; the answer shows what was saved (the cache refreshes only after commit). */
    @Transactional
    public GeneralView update(GeneralSettings values, int version) {
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        try {
            Pattern.compile(values.employeeIdPattern());
        } catch (PatternSyntaxException e) {
            errors.add(new ErrorResponse.FieldError("employeeIdPattern", "This is not a valid regular expression."));
        }
        if (!DATE_FORMAT.matcher(values.dateFormat()).matches()) {
            errors.add(new ErrorResponse.FieldError(
                    "dateFormat", "Use day, month and year tokens such as D MMM YYYY or DD/MM/YYYY."));
        }
        if (!errors.isEmpty()) {
            throw new Errors.BusinessRule("VALIDATION", "Some settings are not valid.", errors);
        }
        return view(settings.update(SettingsArea.GENERAL, values, version));
    }

    private GeneralView view(SettingsService.Versioned<GeneralSettings> current) {
        return new GeneralView(current.value(), current.version(), logo.url());
    }
}
