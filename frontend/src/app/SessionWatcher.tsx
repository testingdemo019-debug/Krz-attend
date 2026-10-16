/**
 * Session expiry (F9, the prototype's idle timeout): the idle warning appears a minute before the session ends, with
 * "Stay signed in" (POST /me/keepalive) and "Sign out"; at expiry the sign-in screen says "Session expired". Mouse
 * and keyboard activity sends at most one keepalive per 5 minutes; every user-initiated request also extends the
 * session on the server, so the local deadline follows those too.
 */
import { useEffect, useRef, useState } from 'react';
import { onUserActivitySent } from '@/api/client';
import { keepalive, type Me } from '@/api/me';
import { Modal } from '@/components/feedback/Modal';
import { Button } from '@/components/ui/Button';
import { toMillis } from '@/lib/format';

const KEEPALIVE_EVERY_MS = 5 * 60_000;
const WARN_BEFORE_MS = 60_000;
const ACTIVITY_EVENTS = ['mousedown', 'keydown', 'touchstart', 'wheel'] as const;

export function SessionWatcher({
  session,
  onExpired,
  onSignOut,
}: {
  session: Me['session'];
  onExpired: () => void;
  onSignOut: () => void;
}) {
  const absolute = toMillis(session.absoluteExpiresAt);
  const idleMs = session.idleTimeoutMin * 60_000;
  const deadline = useRef(toMillis(session.expiresAt));
  const lastKeepalive = useRef<number | null>(null);
  const [left, setLeft] = useState<number | null>(null);
  const warning = left !== null && left <= WARN_BEFORE_MS;

  useEffect(() => {
    deadline.current = toMillis(session.expiresAt);
    lastKeepalive.current ??= Date.now();
  }, [session.expiresAt]);

  useEffect(
    () =>
      onUserActivitySent(() => {
        deadline.current = Math.min(Date.now() + idleMs, absolute);
      }),
    [idleMs, absolute],
  );

  useEffect(() => {
    const touch = (): void => {
      if (warning || Date.now() - (lastKeepalive.current ?? 0) < KEEPALIVE_EVERY_MS) return;
      lastKeepalive.current = Date.now();
      keepalive().catch(() => undefined);
    };
    for (const event of ACTIVITY_EVENTS) document.addEventListener(event, touch, { passive: true });
    return () => {
      for (const event of ACTIVITY_EVENTS) document.removeEventListener(event, touch);
    };
  }, [warning]);

  useEffect(() => {
    const timer = setInterval(() => {
      const remaining = deadline.current - Date.now();
      if (remaining <= 0) {
        clearInterval(timer);
        onExpired();
        return;
      }
      setLeft(remaining <= WARN_BEFORE_MS ? remaining : null);
    }, 1_000);
    return () => {
      clearInterval(timer);
    };
  }, [onExpired]);

  const stay = (): void => {
    lastKeepalive.current = Date.now();
    keepalive()
      .then(() => {
        setLeft(null);
      })
      .catch(() => undefined);
  };

  return (
    <Modal
      open={warning}
      onClose={stay}
      dismissable={false}
      size="sm"
      title="Your session is about to expire"
      footer={
        <>
          <Button onClick={onSignOut}>Sign out</Button>
          <Button variant="primary" onClick={stay}>
            Stay signed in
          </Button>
        </>
      }
    >
      <p>
        For your security, you’ll be signed out in <b>0:{String(Math.ceil((left ?? 0) / 1000)).padStart(2, '0')}</b>{' '}
        because there has been no activity.
      </p>
    </Modal>
  );
}
