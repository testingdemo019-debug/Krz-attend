/** Settings → Enrolment & quality: what a good enrolment needs, and how liveness failures are handled. */
import { useEnrolmentSettings, useSaveEnrolmentSettings, type EnrolmentSettingsView } from '@/api/settings';
import { ErrorState } from '@/components/feedback/EmptyState';
import { Switch } from '@/components/form/Switch';
import { SkeletonRows } from '@/components/ui/Skeleton';
import { useDraft } from '@/lib/useDraft';
import { SettingField, SettingsCard } from './SettingsCard';

const TITLE = 'Enrolment & quality';
const SUB = 'What a good enrolment needs, and how liveness failures are handled.';

type Values = EnrolmentSettingsView['settings'];
type NumberKey = { [K in keyof Values]: Values[K] extends number ? K : never }[keyof Values];

export function EnrolmentSettings() {
  const view = useEnrolmentSettings();
  if (view.isError) return <ErrorState what="the settings" onRetry={() => void view.refetch()} />;
  if (!view.data) {
    return (
      <SettingsCard title={TITLE} sub={SUB}>
        <SkeletonRows rows={5} columns={2} />
      </SettingsCard>
    );
  }
  return (
    <EnrolmentForm
      view={view.data}
      reload={() => {
        void view.refetch();
      }}
    />
  );
}

function EnrolmentForm({ view, reload }: { view: EnrolmentSettingsView; reload: () => void }) {
  const save = useSaveEnrolmentSettings();
  const draft = useDraft({
    title: TITLE,
    source: view.settings,
    save: settings => save.mutateAsync({ settings, version: view.version }),
    onStale: reload,
  });
  const d = draft.value;
  const select = (
    key: NumberKey,
    label: string,
    options: readonly number[],
    text: (n: number) => string,
    help?: string,
  ) => (
    <SettingField label={label} htmlFor={`en-${key}`} {...(help ? { help } : {})}>
      <select
        id={`en-${key}`}
        className="select"
        value={d[key]}
        onChange={e => {
          draft.set({ ...d, [key]: Number(e.target.value) });
        }}
      >
        {[...new Set([...options, d[key]])]
          .sort((a, b) => a - b)
          .map(n => (
            <option key={n} value={n}>
              {text(n)}
            </option>
          ))}
      </select>
    </SettingField>
  );
  return (
    <SettingsCard title={TITLE} sub={SUB} draft={draft}>
      <div className="form-grid">
        {select('minGood', 'Good images needed', [3, 4, 5], n => `${String(n)} images`, 'Out of the 5 guided poses.')}
        {select('minQuality', 'Minimum image quality', [60, 65, 70, 75, 80], n => `Quality ${String(n)}`)}
        {select(
          'minEyePx',
          'Minimum face size',
          [70, 80, 90, 100, 120],
          n => `${String(n)} px between the eyes`,
          'Larger than the camera gate, so templates are sharp.',
        )}
        {select(
          'dupThreshold',
          'Duplicate check similarity',
          [0.5, 0.55, 0.6, 0.65],
          n => n.toFixed(2),
          'Warns when a new face looks like an enrolled employee.',
        )}
        <SettingField label="Liveness during enrolment">
          <Switch
            checked={d.livenessRequired}
            label={d.livenessRequired ? 'Required (blink and head turn)' : 'Off'}
            onChange={e => {
              draft.set({ ...d, livenessRequired: e.target.checked });
            }}
          />
        </SettingField>
        <SettingField label="Photo upload">
          <Switch
            checked={d.allowUpload}
            label={d.allowUpload ? 'Allowed (no liveness)' : 'Live camera only'}
            onChange={e => {
              draft.set({ ...d, allowUpload: e.target.checked });
            }}
          />
        </SettingField>
        {select('lockFailures', 'Lock enrolment after', [3, 5, 7], n => `${String(n)} failed liveness checks`)}
        {select('lockWindowMin', 'Within', [3, 5, 10], n => `${String(n)} minutes`)}
        {select(
          'lockMinutes',
          'Lock for',
          [15, 30, 60],
          n => `${String(n)} minutes`,
          'A System Administrator can unlock early.',
        )}
      </div>
    </SettingsCard>
  );
}
