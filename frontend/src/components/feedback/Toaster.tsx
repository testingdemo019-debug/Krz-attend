import { Icon, type IconName } from '@/components/icons/Icon';
import { dismissToast, useToasts, type ToastType } from './toast';
import './Toaster.module.css';

const ICONS: Record<ToastType, IconName> = {
  success: 'checkCircle',
  error: 'alertCircle',
  warn: 'alert',
  info: 'info',
};
const LEAVING = { opacity: 0, transform: 'translateY(6px)', transition: '.15s' } as const;

export function Toaster() {
  const toasts = useToasts();
  return (
    <div className="toast-stack" role="status" aria-live="polite">
      {toasts.map(t => (
        <div key={t.id} className={`toast ${t.type}`} style={t.leaving ? LEAVING : undefined}>
          <span className="t-ic">
            <Icon name={ICONS[t.type]} />
          </span>
          <span className="grow">{t.message}</span>
          {t.logged && (
            <span className="t-logged" title="Written to the audit log">
              Logged
            </span>
          )}
          {t.action && (
            <button
              type="button"
              className="t-act"
              onClick={() => {
                t.action?.run();
                dismissToast(t.id);
              }}
            >
              {t.action.label}
            </button>
          )}
          <button
            type="button"
            className="t-close"
            aria-label="Dismiss"
            onClick={() => {
              dismissToast(t.id);
            }}
          >
            <Icon name="x" size="sm" />
          </button>
        </div>
      ))}
    </div>
  );
}
