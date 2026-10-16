/** "Sync now" (the prototype's emp-sync): a directory sync run now, on Employees and Settings → Microsoft sign-in. */
import { useDirectorySync } from '@/api/employees';
import { toast } from '@/components/feedback/toast';
import { PermissionButton } from '@/components/ui/PermissionButton';
import { plural } from '@/lib/text';

export function SyncNowButton({ size }: { size?: 'sm' }) {
  const sync = useDirectorySync();
  return (
    <PermissionButton
      permission="settings.integrations"
      icon="sync"
      {...(size ? { size } : { iconSize: 'default' as const })}
      pending={sync.isPending}
      onClick={() => {
        sync.mutate(undefined, {
          onSuccess: run => {
            toast(
              `Directory sync finished — ${String(run.added)} added, ${String(run.updated)} updated${
                run.warnings.length ? `, ${plural(run.warnings.length, 'warning')}` : ''
              }`,
              { type: run.warnings.length ? 'warn' : 'success', logged: true },
            );
          },
          onError: error => toast(error.message, { type: 'error' }),
        });
      }}
    >
      {sync.isPending ? 'Syncing…' : 'Sync now'}
    </PermissionButton>
  );
}
