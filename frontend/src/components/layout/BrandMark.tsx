/**
 * The brand tile of the sidebar, the sign-in screens and Settings → General: the organisation's uploaded logo when
 * there is one (kept whole, square or wide, on a white tile since its own background is unknown), otherwise the KRZ
 * Technologies mark straight on the page in the theme's colours (the "KRZ" wordmark; the "K" alone where the collapsed
 * sidebar leaves only a square; D-121).
 */
import type { CSSProperties } from 'react';
import { cx } from '@/lib/cx';
import { KrzK, KrzWordmark } from './KrzLogo';
import './Sidebar.module.css';

export function BrandMark({
  logoUrl,
  size,
  alt = '',
  style,
}: {
  logoUrl: string | null | undefined;
  /** The tile's height in pixels; the width follows the logo's shape. */
  size?: number;
  alt?: string;
  style?: CSSProperties;
}) {
  if (logoUrl) {
    return (
      <div className="brand-logo logo" style={size ? { height: size, ...style } : style}>
        <img src={logoUrl} alt={alt} />
      </div>
    );
  }
  return (
    <div
      className={cx('brand-logo', 'krz')}
      style={size ? { height: size, ...style } : style}
      role={alt ? 'img' : undefined}
      aria-label={alt || undefined}
    >
      <KrzWordmark className="krz-word" />
      <KrzK className="krz-k" />
    </div>
  );
}
