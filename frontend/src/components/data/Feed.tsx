import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { cx } from '@/lib/cx';
import './Feed.module.css';

/** One row of a card list (.feed-item): a link when it leads somewhere, otherwise plain. "new" flashes it once. */
export function FeedItem({
  to,
  isNew = false,
  className,
  children,
}: {
  to?: string | undefined;
  isNew?: boolean;
  className?: string;
  children: ReactNode;
}) {
  const classes = cx('feed-item', isNew && 'new', className);
  return to ? (
    <Link className={classes} to={to}>
      {children}
    </Link>
  ) : (
    <div className={classes}>{children}</div>
  );
}
