import { Fragment } from 'react';
import { NavLink } from 'react-router';
import { Icon } from '@/components/icons/Icon';
import { BrandMark } from '@/components/layout/BrandMark';
import { cx } from '@/lib/cx';
import type { NavBadge, NavSection } from '@/lib/nav';
import './Sidebar.module.css';

export interface SidebarProps {
  sections: readonly NavSection[];
  orgName: string;
  logoUrl: string | null;
  collapsed: boolean;
  onToggle: () => void;
  badges: Partial<Record<NavBadge, number>>;
}

/**
 * The prototype's sidebarHTML. Collapsed, the labels hide and items show a red dot instead of their count; their
 * names come from the native title (the prototype's left-side tooltip was clipped by the sidebar, F6.14).
 */
export function Sidebar({ sections, orgName, logoUrl, collapsed, onToggle, badges }: SidebarProps) {
  return (
    <nav className="sidebar" aria-label="Main navigation" id="sidebar">
      <div className="brand">
        <BrandMark logoUrl={logoUrl} />
        <div className="brand-text">
          <div className="brand-name">KRZ Attend</div>
          <div className="brand-sub">{orgName}</div>
        </div>
      </div>
      <div className="nav">
        {sections.map(section => (
          <Fragment key={section.section}>
            <div className="nav-section">{section.section}</div>
            {section.items.map(item => {
              const count = item.badge ? (badges[item.badge] ?? 0) : 0;
              return (
                <NavLink
                  key={item.to}
                  to={item.to}
                  className={({ isActive }) => cx('nav-item', isActive && 'active')}
                  title={collapsed ? item.label : undefined}
                >
                  <Icon name={item.icon} />
                  <span className="nav-label">{item.label}</span>
                  {count > 0 && (
                    <>
                      <span
                        className={cx('badge', item.badge !== 'review' && 'neutral')}
                        aria-label={`${count} pending`}
                      >
                        {count}
                      </span>
                      <span className="dot-badge" />
                    </>
                  )}
                </NavLink>
              );
            })}
          </Fragment>
        ))}
      </div>
      <div className="sidebar-foot">
        <button
          type="button"
          className="nav-item"
          onClick={onToggle}
          aria-label={`${collapsed ? 'Expand' : 'Collapse'} sidebar`}
        >
          <Icon name="sidebar" />
          <span className="nav-label">Collapse</span>
        </button>
      </div>
    </nav>
  );
}
