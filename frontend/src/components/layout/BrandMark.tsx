/**
 * The brand tile of the sidebar, the sign-in screens and Settings → General: the organisation's uploaded logo when
 * there is one (kept whole, square or wide), otherwise the KRZ Technologies mark (the "KRZ" wordmark; the "K" alone
 * where the collapsed sidebar leaves only a square).
 */
import type { CSSProperties } from 'react';
import wordmark from '@/assets/brand/krz-wordmark.svg';
import kMark from '@/assets/brand/krz-k.svg';
import { cx } from '@/lib/cx';
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
      <img className="krz-word" src={wordmark} alt="" />
      <img className="krz-k" src={kMark} alt="" />
    </div>
  );
}
