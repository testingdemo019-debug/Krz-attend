package com.krztechnologies.attend.notification.service;

import com.krztechnologies.attend.common.app.AppProperties;
import com.krztechnologies.attend.common.settings.SettingsService;
import com.krztechnologies.attend.notification.domain.RenderedEmail;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Renders e-mails from {@code templates/email/<key>.html} and {@code .txt} with Thymeleaf (escaped by default). Links
 * are absolute and built only from APP_BASE_URL. No images of faces are ever included.
 */
@Component
public class EmailComposer {

    private final TemplateEngine html = engine(TemplateMode.HTML, ".html");
    private final TemplateEngine text = engine(TemplateMode.TEXT, ".txt");
    private final AppProperties app;
    private final SettingsService settings;

    public EmailComposer(AppProperties app, SettingsService settings) {
        this.app = app;
        this.settings = settings;
    }

    /**
     * @param link app-relative path (e.g. {@code /settings/users?tab=requests}) or null
     */
    public RenderedEmail compose(String templateKey, String subject, Map<String, Object> variables, String link) {
        Map<String, Object> all = new HashMap<>(variables);
        String base = app.baseUrl().replaceAll("/$", "");
        all.put("orgName", settings.general().orgName());
        all.put("subject", subject);
        all.put("link", link == null ? null : base + link);
        all.put("notificationsUrl", base + "/settings/notifications");
        Context context = new Context(Locale.UK, all);
        return new RenderedEmail(
                templateKey, subject, html.process(templateKey, context), text.process(templateKey, context));
    }

    private static TemplateEngine engine(TemplateMode mode, String suffix) {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/email/");
        resolver.setSuffix(suffix);
        resolver.setTemplateMode(mode);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);
        var engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
