import { Fragment } from 'react';
import { Link } from 'react-router';
import { Icon } from '@/components/icons/Icon';
import './Breadcrumbs.module.css';

export interface Crumb {
  label: string;
  /** Omitted for the current page (the last crumb). */
  to?: string;
}

export function Breadcrumbs({ crumbs }: { crumbs: readonly Crumb[] }) {
  return (
    <nav className="breadcrumbs" aria-label="Breadcrumb">
      {crumbs.map((crumb, i) =>
        i < crumbs.length - 1 && crumb.to ? (
          <Fragment key={crumb.label}>
            <Link to={crumb.to}>{crumb.label}</Link>
            <Icon name="chevRight" size="sm" />
          </Fragment>
        ) : (
          <span key={crumb.label} aria-current="page">
            {crumb.label}
          </span>
        ),
      )}
    </nav>
  );
}
