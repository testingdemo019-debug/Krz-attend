/**
 * A crossing's face or body crop (F7): GET /media/crops/<event>/<face|body> returns the blurred variant; "Reveal"
 * (people.view.sensitive only) loads ?reveal=true, which the server writes to the audit log. A purged crop (410)
 * shows the "deleted by retention" placeholder.
 */
import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useState, type CSSProperties } from 'react';
import { ApiError, request } from '@/api/client';
import { toast } from '@/components/feedback/toast';
import { Icon } from '@/components/icons/Icon';
import { tip } from '@/components/ui/Tooltip';
import { cx } from '@/lib/cx';
import { useCan } from '@/lib/permissions';
import './CropImage.module.css';

export type CropSize = 'lg' | 'xl';
export type CropKind = 'face' | 'body';

function useObjectUrl(blob: Blob | undefined): string | null {
  const url = useMemo(() => (blob ? URL.createObjectURL(blob) : null), [blob]);
  useEffect(
    () => () => {
      if (url) URL.revokeObjectURL(url);
    },
    [url],
  );
  return url;
}

/** The crop as an object URL (null while loading), and whether retention deleted it. */
function useCrop(crossing: string, kind: CropKind, available: boolean, revealed: boolean) {
  const crop = useQuery({
    queryKey: ['crops', crossing, kind, revealed],
    queryFn: ({ signal }) =>
      request<Blob>(`/media/crops/${encodeURIComponent(crossing)}/${kind}`, {
        signal,
        responseType: 'blob',
        query: { reveal: revealed || undefined },
        userActivity: revealed,
      }),
    enabled: available,
    staleTime: Infinity,
    retry: false,
  });
  return {
    url: useObjectUrl(crop.data),
    purged: crop.error instanceof ApiError && crop.error.status === 410,
  };
}

function Placeholder({
  className,
  purged,
  style,
}: {
  className: string;
  purged: boolean;
  style: CSSProperties | undefined;
}) {
  return (
    <span className={className} title={purged ? 'Deleted by retention' : 'No crop'} style={style}>
      <span className="grid-center" style={{ width: '100%', height: '100%', color: 'var(--text-3)' }}>
        <Icon name={purged ? 'trash' : 'image'} size="sm" />
      </span>
    </span>
  );
}

export function CropImage({
  crossing,
  kind = 'face',
  size,
  available = true,
  revealable = true,
  onOpen,
  openLabel,
  style,
}: {
  crossing: string;
  kind?: CropKind;
  size?: CropSize | undefined;
  /** The crossing has this crop (EventRow.faceCrop / bodyCrop). */
  available?: boolean;
  /** False inside a link row: no nested Reveal button (reveal from the event instead, F6.14). */
  revealable?: boolean;
  /** The crop is a button that opens something (the Review Queue's crop gallery) instead of revealing. */
  onOpen?: (() => void) | undefined;
  openLabel?: string | undefined;
  /** Size overrides, e.g. filling a gallery tile. */
  style?: CSSProperties | undefined;
}) {
  const canReveal = useCan('people.view.sensitive');
  const [revealed, setRevealed] = useState(false);
  const { url, purged } = useCrop(crossing, kind, available, revealed);
  const classes = cx('crop', size);

  if (!available || purged) return <Placeholder className={classes} purged={purged} style={style} />;
  const image = url ? <img src={url} alt="" /> : null;
  if (onOpen) {
    return (
      <button type="button" className={cx(classes, 'blurred')} aria-label={openLabel} style={style} onClick={onOpen}>
        {image}
      </button>
    );
  }
  if (revealed) {
    return (
      <span className={classes} aria-label={`${kind === 'face' ? 'Face' : 'Body'} crop (revealed)`} style={style}>
        {image}
      </span>
    );
  }
  if (!canReveal || !revealable) {
    return (
      <span className={cx(classes, 'blurred')} aria-label={`Blurred ${kind} crop`} style={style}>
        {image}
      </span>
    );
  }
  return (
    <button
      type="button"
      className={cx(classes, 'blurred')}
      aria-label={`Blurred ${kind} crop — click to reveal (logged)`}
      style={style}
      {...tip('Click to reveal (logged)')}
      onClick={event => {
        event.preventDefault();
        event.stopPropagation();
        setRevealed(true);
        toast('Face crop revealed', { type: 'info', logged: true, timeout: 2200 });
      }}
    >
      {image}
    </button>
  );
}

/**
 * The prototype's snapshot frame (.frame-thumb): the crossing's body crop, blurred like every crop, since the system
 * keeps crops rather than whole frames.
 */
export function FrameThumb({
  crossing,
  available,
  className,
}: {
  crossing: string;
  available: boolean;
  className?: string;
}) {
  const { url, purged } = useCrop(crossing, 'body', available, false);
  if (!available || purged) {
    return (
      <div className={cx('frame-thumb', className)} title={purged ? 'Deleted by retention' : 'No body crop'}>
        <span className="grid-center" style={{ width: '100%', height: '100%', color: 'var(--text-3)' }}>
          <Icon name={purged ? 'trash' : 'image'} />
        </span>
      </div>
    );
  }
  return (
    <div className={cx('frame-thumb', className)} role="img" aria-label="Blurred body crop">
      {url && <img src={url} alt="" style={{ objectFit: 'contain' }} />}
    </div>
  );
}
