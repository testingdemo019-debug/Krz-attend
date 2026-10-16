package com.krztechnologies.attend.privacy.service;

import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.error.Errors;
import com.krztechnologies.attend.common.security.CurrentUser;
import com.krztechnologies.attend.common.time.TimeService;
import com.krztechnologies.attend.privacy.domain.ConsentNotice;
import com.krztechnologies.attend.privacy.repository.ConsentNoticeRepository;
import com.krztechnologies.attend.privacy.repository.PrivacyQueries;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settings → Privacy &amp; retention → Consent notice: the published versions and publishing a new one. A new version
 * deactivates the previous one (exactly one is active). Employees who consented to an older version keep their
 * consent until their next enrolment, where they are asked again.
 */
@Service
public class ConsentNoticeService {

    static final int MIN_LENGTH = 80;
    static final int MAX_LENGTH = 10_000;

    private final ConsentNoticeRepository notices;
    private final PrivacyQueries queries;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final TimeService time;

    public ConsentNoticeService(
            ConsentNoticeRepository notices,
            PrivacyQueries queries,
            CurrentUser currentUser,
            AuditService audit,
            TimeService time) {
        this.notices = notices;
        this.queries = queries;
        this.currentUser = currentUser;
        this.audit = audit;
        this.time = time;
    }

    /**
     * @param publishedBy who published it ("System" for the seeded first version)
     */
    public record NoticeView(int version, String text, Instant publishedAt, String publishedBy, boolean active) {}

    /**
     * @param notices newest first
     * @param consentedToOlderVersion people whose consent refers to an older version (asked again at re-enrolment)
     */
    public record NoticesView(List<NoticeView> notices, long consentedToOlderVersion) {}

    @Transactional(readOnly = true)
    public NoticesView list() {
        List<NoticeView> all = notices.findAllByOrderByNoticeVersionDesc().stream()
                .map(ConsentNoticeService::toView)
                .toList();
        long older = all.stream()
                .filter(NoticeView::active)
                .findFirst()
                .map(active -> queries.consentedToOlderNotice(active.version()))
                .orElse(0L);
        return new NoticesView(all, older);
    }

    /** The notice consent is given against now (enrolment). */
    @Transactional(readOnly = true)
    public NoticeView active() {
        return notices.findByActiveTrue()
                .map(ConsentNoticeService::toView)
                .orElseThrow(() -> new Errors.NotFound("No consent notice is published."));
    }

    /** Publishes the text as the next version and makes it the only active one. */
    @Transactional
    public NoticeView publish(String text) {
        String trimmed = text.strip();
        if (trimmed.length() < MIN_LENGTH || trimmed.length() > MAX_LENGTH) {
            throw new Errors.BusinessRule(
                    "NOTICE_LENGTH",
                    "The notice must be between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters long.");
        }
        queries.lockNotices();
        Optional<ConsentNotice> current = notices.findByActiveTrue();
        if (current.isPresent() && current.get().getText().strip().equals(trimmed)) {
            throw new Errors.BusinessRule("NOTICE_UNCHANGED", "Change the text before publishing a new version.");
        }
        int next = notices.latestVersion() + 1;
        // The previous version goes inactive first: the database allows only one active notice at a time.
        current.ifPresent(notice -> {
            notice.deactivate();
            notices.saveAndFlush(notice);
        });
        var me = currentUser.require();
        ConsentNotice published =
                notices.save(new ConsentNotice(next, trimmed, time.now(), me.employeeId(), me.displayName()));
        audit.record(
                AuditCategory.PRIVACY,
                "Published consent notice",
                "v" + next,
                current.map(n -> "v" + n.getNoticeVersion()).orElse(null),
                "v" + next,
                null);
        return toView(published);
    }

    private static NoticeView toView(ConsentNotice notice) {
        return new NoticeView(
                notice.getNoticeVersion(),
                notice.getText(),
                notice.getPublishedAt(),
                notice.getPublishedByLabel(),
                notice.isActive());
    }
}
