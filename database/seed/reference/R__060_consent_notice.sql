-- R__060_consent_notice.sql
-- Purpose: consent notice v1, based on the prototype's v3 text with the real domain, neutral location wording and
-- retention figures matching the privacy settings (same-day body features: "within 20 hours").
-- LEGAL REVIEW REQUIRED BEFORE GO-LIVE (PENDING P-12, P-16). Created only when no notice exists.

INSERT INTO attend.consent_notice (version, text, published_by_label, active)
SELECT 1,
       'KRZ Technologies uses face recognition at the office entrance to record attendance. One camera at the entrance records when you come in and when you leave. When you leave with your back to the camera, the system matches your clothing and build to how you came in that day; this body match is used only on the same day.

What we collect: face images captured at enrolment and at the entrance camera, face templates (numerical representations), same-day body appearance features, and attendance punches.
Why: to record attendance. We do not use it for any other purpose.
Retention: face templates are kept while you work here and deleted when you leave or withdraw consent. Entrance crops are kept for 30 days; crops of unknown faces for 24 hours; same-day body features are deleted within 20 hours.
Your choices: enrolment is voluntary. If you opt out or withdraw consent, HR records your attendance in KRZ HRMS instead. You can ask to see, correct or erase your data through HR or the grievance officer.
Contact: grievance.officer@krztechnologies.com',
       'System',
       true
 WHERE NOT EXISTS (SELECT 1 FROM attend.consent_notice);
