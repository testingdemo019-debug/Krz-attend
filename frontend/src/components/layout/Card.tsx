import type { CSSProperties, ReactNode } from 'react';
import { cx } from '@/lib/cx';
import './Card.module.css';

/** The prototype's .card with an optional .card-head (h2 title + actions) and a .card-body (flush = no padding). */
export function Card({
  title,
  titleLevel = 2,
  actions,
  flush = false,
  pad = false,
  bodyClassName,
  className,
  style,
  children,
}: {
  title?: ReactNode;
  titleLevel?: 2 | 3;
  actions?: ReactNode;
  /** No body padding: lists and tables fill the card. */
  flush?: boolean;
  /** .card.pad: padding on the card itself, no head or body wrappers. */
  pad?: boolean;
  bodyClassName?: string;
  className?: string;
  style?: CSSProperties;
  children?: ReactNode;
}) {
  if (pad) {
    return (
      <div className={cx('card', 'pad', className)} style={style}>
        {children}
      </div>
    );
  }
  const Heading = titleLevel === 3 ? 'h3' : 'h2';
  return (
    <div className={cx('card', className)} style={style}>
      {(title !== undefined || actions !== undefined) && (
        <div className="card-head">
          {title !== undefined && <Heading>{title}</Heading>}
          {actions}
        </div>
      )}
      <CardBody flush={flush} className={bodyClassName}>
        {children}
      </CardBody>
    </div>
  );
}

export function CardBody({
  flush = false,
  className,
  style,
  children,
}: {
  flush?: boolean;
  className?: string | undefined;
  style?: CSSProperties;
  children?: ReactNode;
}) {
  return (
    <div className={cx('card-body', flush && 'flush', className)} style={style}>
      {children}
    </div>
  );
}
