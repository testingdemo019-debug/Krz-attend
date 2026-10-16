import type { CSSProperties, ReactNode } from 'react';
import './Kbd.module.css';

export function Kbd({
  children,
  hidden = false,
  style,
}: {
  children: ReactNode;
  hidden?: boolean;
  style?: CSSProperties;
}) {
  return (
    <span className="kbd" aria-hidden={hidden || undefined} style={style}>
      {children}
    </span>
  );
}
