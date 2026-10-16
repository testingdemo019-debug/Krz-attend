import type { ReactNode } from 'react';
import { Icon, type IconName } from '@/components/icons/Icon';
import { Button } from '@/components/ui/Button';
import './EmptyState.module.css';

/** The prototype's emptyState: round icon, title, text and an optional action. */
export function EmptyState({
  icon = 'inbox',
  title,
  text,
  action,
}: {
  icon?: IconName;
  title: string;
  text?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="empty">
      <div className="empty-ic">
        <Icon name={icon} size="lg" />
      </div>
      <h3>{title}</h3>
      {text !== undefined && <p>{text}</p>}
      {action}
    </div>
  );
}

/** The prototype's errorState: "Couldn’t load …" with Retry (F5: without its "(simulated error)" wording). */
export function ErrorState({ what = 'this data', onRetry }: { what?: string; onRetry: () => void }) {
  return (
    <div className="empty error-state" role="alert">
      <div className="empty-ic">
        <Icon name="alertCircle" size="lg" />
      </div>
      <h3>Couldn’t load {what}</h3>
      <p>The server didn’t respond. Check your connection and try again.</p>
      <Button icon="refresh" iconSize="default" onClick={onRetry}>
        Retry
      </Button>
    </div>
  );
}
