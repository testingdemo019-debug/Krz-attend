import type { ReactNode } from 'react';
import { cx } from '@/lib/cx';
import './AppShell.module.css';

/** The prototype's shell: skip link, sidebar, and the main column with the topbar and the page. */
export function AppShell({
  collapsed,
  sidebar,
  topbar,
  full = false,
  children,
}: {
  collapsed: boolean;
  sidebar: ReactNode;
  topbar: ReactNode;
  /** Pages that use the whole width (.content.full). */
  full?: boolean;
  children: ReactNode;
}) {
  return (
    <>
      <a className="skip-link" href="#page">
        Skip to content
      </a>
      <div className={cx('shell', collapsed && 'collapsed')} id="shell">
        {sidebar}
        <div className="main">
          {topbar}
          <main className={cx('content', full && 'full')} id="page" tabIndex={-1}>
            {children}
          </main>
        </div>
      </div>
    </>
  );
}
