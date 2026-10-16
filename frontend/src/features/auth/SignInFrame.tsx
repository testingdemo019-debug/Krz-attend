/** What every sign-in and access screen shares: the card, the brand header, and the footer with the privacy notice. */
import { useQuery } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { request } from '@/api/client';
import { useBranding, type Branding } from '@/api/me';
import type { components } from '@/api/schema';
import { NoticeText } from '@/components/domain/NoticeText';
import { BrandMark } from '@/components/layout/BrandMark';
import { Modal } from '@/components/feedback/Modal';
import { fmtDate } from '@/lib/format';
import './SignIn.module.css';

type PublicNotice = components['schemas']['PublicNoticeController.PublicNotice'];

export function BrandHeader({ branding }: { branding: Branding | undefined }) {
  return (
    <div className="row gap-12 mb-16">
      <BrandMark logoUrl={branding?.logoUrl} size={44} style={{ borderRadius: 11 }} />
      <div>
        <div style={{ fontWeight: 700, fontSize: 20 }}>KRZ Attend</div>
        <div className="muted small">{branding?.orgName}</div>
      </div>
    </div>
  );
}

function PrivacyNotice({ open, onClose }: { open: boolean; onClose: () => void }) {
  const notice = useQuery({
    queryKey: ['public', 'privacy-notice'],
    queryFn: ({ signal }) => request<PublicNotice>('/public/privacy-notice', { signal, allowUnauthorized: true }),
    enabled: open,
    staleTime: 5 * 60_000,
  }).data;
  return (
    <Modal
      open={open && notice !== undefined}
      onClose={onClose}
      title={`Face recognition notice · v${notice?.version ?? ''}`}
      sub={notice ? `Published ${fmtDate(notice.publishedAt)}` : undefined}
    >
      <NoticeText text={notice?.text ?? ''} />
    </Modal>
  );
}

/** The prototype's .signin page: the card, then the footer (privacy notice, version, "Internal use only"). */
export function SignInFrame({
  wide = false,
  live = false,
  children,
}: {
  wide?: boolean;
  /** The card announces its changes (redirect and checking steps). */
  live?: boolean;
  children: (branding: Branding | undefined) => ReactNode;
}) {
  const branding = useBranding().data;
  const [notice, setNotice] = useState(false);
  return (
    <div className="signin">
      <main
        className="signin-card"
        style={wide ? { width: 'min(520px,100%)' } : undefined}
        aria-labelledby="si-title"
        aria-live={live ? 'polite' : undefined}
      >
        {children(branding)}
      </main>
      <footer className="signin-foot">
        <a
          href="#privacy"
          onClick={event => {
            event.preventDefault();
            setNotice(true);
          }}
        >
          Privacy notice
        </a>
        <span aria-hidden="true">·</span>
        <span>KRZ Attend {branding?.appVersion}</span>
        <span aria-hidden="true">·</span>
        <span>Internal use only</span>
      </footer>
      <PrivacyNotice
        open={notice}
        onClose={() => {
          setNotice(false);
        }}
      />
    </div>
  );
}
