import { useStatusSummary } from '@/api/shell';
import { HealthRows } from '@/components/domain/HealthRow';
import { ButtonLink } from '@/components/ui/Button';
import { MenuLabel, Popover, PopoverClose } from '@/components/ui/Popover';
import { useCan } from '@/lib/permissions';
import { STATUS_LEVEL } from '@/lib/vocabulary';

/** The topbar status pill and its popover (the prototype's systemHealth + statusRowsHTML), for every user. */
export function StatusPill() {
  const { data } = useStatusSummary();
  const canSystem = useCan('settings.integrations');
  if (!data) return null;
  const level = STATUS_LEVEL[data.level] === 'off' ? 'ok' : STATUS_LEVEL[data.level];
  return (
    <Popover
      align="right"
      trigger={
        <button
          type="button"
          className={`status-pill ${level}`}
          aria-haspopup="true"
          aria-label={`System status: ${data.text}`}
        >
          <span className="dot" />
          <span className="sp-text">{data.text}</span>
        </button>
      }
    >
      <div style={{ padding: '6px 8px', minWidth: 330 }}>
        <MenuLabel style={{ paddingLeft: 0 }}>System status</MenuLabel>
        <HealthRows rows={data.rows} />
        {canSystem && (
          <PopoverClose asChild>
            <ButtonLink size="sm" className="mt-8" style={{ width: '100%' }} to="/settings/system">
              Open system status
            </ButtonLink>
          </PopoverClose>
        )}
      </div>
    </Popover>
  );
}
