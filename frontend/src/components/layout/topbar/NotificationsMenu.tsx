import { useState } from 'react';
import { Link } from 'react-router';
import { useMarkAllRead, useMarkRead, useNotifications, useUnreadCount } from '@/api/shell';
import { EmptyState } from '@/components/feedback/EmptyState';
import { toast } from '@/components/feedback/toast';
import { Icon } from '@/components/icons/Icon';
import { Button } from '@/components/ui/Button';
import { Popover } from '@/components/ui/Popover';
import { relTime } from '@/lib/format';
import { NOTIFICATION_ICONS } from '@/lib/vocabulary';

/** The bell and its popover: the newest 30, mark read on open, "Mark all as read". */
export function NotificationsMenu() {
  const [open, setOpen] = useState(false);
  const unread = useUnreadCount().data ?? 0;
  const list = useNotifications().data?.items ?? [];
  const markRead = useMarkRead();
  const markAll = useMarkAllRead();
  return (
    <Popover
      align="right"
      open={open}
      onOpenChange={setOpen}
      trigger={
        <button type="button" className="icon-btn" aria-label={`Notifications, ${unread} unread`} aria-haspopup="true">
          <Icon name="bell" />
          {unread > 0 && <span className="badge">{unread}</span>}
        </button>
      }
    >
      {/* The popover's own limit (360 px less its padding, or the window), so the list never scrolls sideways (F6.6). */}
      <div style={{ width: 346, maxWidth: '100%' }}>
        <div className="row between" style={{ padding: '6px 8px' }}>
          <b>Notifications</b>
          {list.some(n => !n.read) && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                markAll.mutate(undefined, {
                  onSuccess: () => toast('All notifications marked as read', { type: 'success' }),
                });
                setOpen(false);
              }}
            >
              Mark all as read
            </Button>
          )}
        </div>
        {list.length ? (
          list.map(n => (
            <Link
              key={n.code}
              className="menu-item"
              to={n.link ?? '/dashboard'}
              style={{ alignItems: 'flex-start' }}
              onClick={() => {
                if (!n.read) markRead.mutate(n.code);
                setOpen(false);
              }}
            >
              <Icon name={NOTIFICATION_ICONS[n.ruleCode] ?? 'bell'} />
              <span className="grow">
                <span style={{ fontWeight: n.read ? 500 : 700 }}>{n.title}</span>
                {n.body && (
                  <>
                    <br />
                    <span className="muted small">{n.body}</span>
                  </>
                )}
                <br />
                <span className="muted xs">{relTime(n.createdAt)}</span>
              </span>
              {!n.read && (
                <span className="sdot" style={{ background: 'var(--accent)', marginTop: 6 }} aria-label="Unread" />
              )}
            </Link>
          ))
        ) : (
          <EmptyState
            icon="bell"
            title="No notifications"
            text="You’re all caught up. Choose what you’re told about in Settings → Notifications."
          />
        )}
      </div>
    </Popover>
  );
}
