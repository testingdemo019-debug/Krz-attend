/**
 * Settings → General: the organisation name, the logo and how times are shown. Times are always IST. The logo is
 * uploaded (or removed) at once, re-encoded by the server: PNG or JPEG up to 512 KB, never SVG (security §7).
 */
import type { ChangeEvent } from 'react';
import { useGeneral, useLogo, useSaveGeneral, type GeneralView } from '@/api/settings';
import { ErrorState } from '@/components/feedback/EmptyState';
import { toast } from '@/components/feedback/toast';
import { Icon } from '@/components/icons/Icon';
import { Button } from '@/components/ui/Button';
import { SkeletonRows } from '@/components/ui/Skeleton';
import { BrandMark } from '@/components/layout/BrandMark';
import { useDraft } from '@/lib/useDraft';
import { SettingField, SettingsCard } from './SettingsCard';

const TITLE = 'General';
const SUB = 'Organisation name, logo and how dates and times are shown. Times are always Indian Standard Time (IST).';
const MAX_LOGO_BYTES = 512 * 1024;

export function GeneralSettings() {
  const general = useGeneral();
  if (general.isError) return <ErrorState what="the settings" onRetry={() => void general.refetch()} />;
  if (!general.data) {
    return (
      <SettingsCard title={TITLE} sub={SUB}>
        <SkeletonRows rows={3} columns={2} />
      </SettingsCard>
    );
  }
  return (
    <GeneralForm
      view={general.data}
      reload={() => {
        void general.refetch();
      }}
    />
  );
}

function GeneralForm({ view, reload }: { view: GeneralView; reload: () => void }) {
  const save = useSaveGeneral();
  const logo = useLogo();
  const draft = useDraft({
    title: TITLE,
    source: view.settings,
    save: d => save.mutateAsync({ ...d, version: view.version }),
    validate: d => (d.orgName.trim() ? null : 'Enter the organisation name.'),
    onStale: reload,
  });
  const d = draft.value;

  const upload = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    if (file.size > MAX_LOGO_BYTES) {
      toast('The logo must be 512 KB or smaller', { type: 'error' });
      return;
    }
    logo.mutate(file, {
      onSuccess: () => toast('Logo updated', { type: 'success', logged: true }),
      onError: error => toast(error.message, { type: 'error' }),
    });
  };

  return (
    <SettingsCard title={TITLE} sub={SUB} draft={draft}>
      <div className="form-grid">
        <SettingField label="Organisation name" htmlFor="g-org">
          <input
            id="g-org"
            className="input"
            value={d.orgName}
            onChange={e => {
              draft.set({ ...d, orgName: e.target.value });
            }}
          />
        </SettingField>
        <SettingField label="Time zone" help="Fixed for this deployment.">
          <input className="input" value="Asia/Kolkata (IST, UTC+05:30)" disabled aria-label="Time zone" />
        </SettingField>
        <SettingField label="Time format" htmlFor="g-tf">
          <select
            id="g-tf"
            className="select"
            value={d.timeFormat}
            onChange={e => {
              draft.set({ ...d, timeFormat: e.target.value });
            }}
          >
            <option value="12h">12-hour (6:05 PM)</option>
            <option value="24h">24-hour (18:05)</option>
          </select>
        </SettingField>
        <SettingField label="Date format" help="Day, short month, year — used everywhere, including exports.">
          <input className="input" value="12 Oct 2026" disabled aria-label="Date format" />
        </SettingField>
        <SettingField
          label="Logo"
          full
          help="Shown in the sidebar and on the sign-in screen, square or wide, at least 128 px high, up to 512 KB. Without one, the KRZ Technologies mark is shown."
        >
          <div className="row gap-12">
            <BrandMark logoUrl={view.logoUrl} size={48} alt="Current logo" />
            <label className="btn btn-sm" aria-busy={logo.isPending || undefined}>
              <Icon name="upload" size="sm" />
              Upload PNG or JPEG
              <input
                type="file"
                accept="image/png,image/jpeg"
                className="sr-only"
                disabled={logo.isPending}
                onChange={upload}
              />
            </label>
            {view.logoUrl && (
              <Button
                size="sm"
                variant="ghost"
                pending={logo.isPending}
                onClick={() => {
                  logo.mutate(null, {
                    onSuccess: () => toast('Logo removed', { type: 'success', logged: true }),
                    onError: error => toast(error.message, { type: 'error' }),
                  });
                }}
              >
                Remove
              </Button>
            )}
          </div>
        </SettingField>
      </div>
    </SettingsCard>
  );
}
