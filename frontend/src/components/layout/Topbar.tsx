import { useState, type RefObject } from 'react';
import type { Me } from '@/api/me';
import { Badge } from '@/components/ui/Badge';
import { IconButton, IconLink } from '@/components/ui/IconButton';
import { cx } from '@/lib/cx';
import type { NavSection } from '@/lib/nav';
import { useCan } from '@/lib/permissions';
import { GlobalSearch } from './topbar/GlobalSearch';
import { NotificationsMenu } from './topbar/NotificationsMenu';
import { StatusPill } from './topbar/StatusPill';
import { UserMenu } from './topbar/UserMenu';
import './Topbar.module.css';

/**
 * The prototype's topbarHTML: menu toggle, global search, status pill, Review Queue, notifications and the user.
 * On phones the search is an icon that opens a full-width field (F6.6).
 */
export function Topbar({
  me,
  sections,
  reviewCount,
  searchRef,
  onToggleSidebar,
  onAccount,
  onShortcuts,
  onSignOut,
}: {
  me: Me;
  sections: readonly NavSection[];
  reviewCount: number;
  searchRef: RefObject<HTMLInputElement | null>;
  onToggleSidebar: () => void;
  onAccount: () => void;
  onShortcuts: () => void;
  onSignOut: () => void;
}) {
  const canReview = useCan('review.resolve');
  const [searching, setSearching] = useState(false);
  return (
    <header className={cx('topbar', searching && 'searching')} id="topbar">
      <IconButton className="tb-menu" icon="menu" label="Toggle sidebar" onClick={onToggleSidebar} />
      <IconButton
        className="tb-search"
        icon="search"
        label="Search"
        onClick={() => {
          setSearching(true);
          requestAnimationFrame(() => searchRef.current?.focus());
        }}
      />
      <GlobalSearch
        sections={sections}
        inputRef={searchRef}
        onBlur={() => {
          setSearching(false);
        }}
      />
      <span className="topbar-spacer" />
      <StatusPill />
      {canReview && (
        <IconLink
          className="tb-review"
          to="/review"
          icon="review"
          label={`Review Queue, ${reviewCount} pending`}
          tooltip="Review Queue"
          tooltipPosition="bottom"
        >
          {reviewCount > 0 && <Badge count={reviewCount} />}
        </IconLink>
      )}
      <NotificationsMenu />
      <UserMenu me={me} onAccount={onAccount} onShortcuts={onShortcuts} onSignOut={onSignOut} />
    </header>
  );
}
