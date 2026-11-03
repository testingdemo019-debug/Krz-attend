/**
 * What every sign-in and access screen shares (D-121): two panels — the brand panel with the KRZ mark, the camera-scan
 * animation and what KRZ Attend does; and the card with the footer (privacy notice, version, "Internal use only"). On
 * a narrow screen the brand panel becomes a band above the card.
 */
import { useQuery } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { request } from '@/api/client';
import { useBranding, type Branding } from '@/api/me';
import type { components } from '@/api/schema';
import { NoticeText } from '@/components/domain/NoticeText';
import { BrandMark } from '@/components/layout/BrandMark';
import { KrzWordmark } from '@/components/layout/KrzLogo';
import { Modal } from '@/components/feedback/Modal';
import { fmtDate } from '@/lib/format';
import { ScanAnimation } from './ScanAnimation';

type PublicNotice = components['schemas']['PublicNoticeController.PublicNotice'];

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

/** The brand panel: the mark (an uploaded logo when there is one), the scan loop and three facts. */
function BrandPanel({ branding }: { branding: Branding | undefined }) {
  return (
    <aside className="signin-hero" aria-label="KRZ Attend">
      <div className="hero-brand">
        {branding?.logoUrl ? (
          <BrandMark logoUrl={branding.logoUrl} size={40} style={{ borderRadius: 10 }} />
        ) : (
          <KrzWordmark className="hero-mark" />
        )}
        <div>
          <div className="hero-name">KRZ Attend</div>
          <div className="hero-org">{branding?.orgName}</div>
        </div>
      </div>
      <ScanAnimation />
      <div className="hero-copy">
        <h2>Walk in. You’re checked in.</h2>
        <p>The camera at the door recognises people as they pass and records attendance by itself.</p>
        <ul className="hero-facts">
          <li>Face and back-view recognition</li>
          <li>Liveness check against photos</li>
          <li>Live attendance, every shift</li>
        </ul>
      </div>
    </aside>
  );
}

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
      <BrandPanel branding={branding} />
      <div className="signin-main">
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
      </div>
      <PrivacyNotice
        open={notice}
        onClose={() => {
          setNotice(false);
        }}
      />
    </div>
  );
}
