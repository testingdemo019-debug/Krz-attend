import './NoticeText.module.css';

/** A consent notice's text, line breaks kept (sign-in, enrolment consent, Settings → Privacy). */
export function NoticeText({ text }: { text: string }) {
  return <div className="notice-text">{text}</div>;
}
