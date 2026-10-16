import { Callout } from '@/components/ui/Callout';
import { Tag } from '@/components/ui/Chip';

/** The prototype's sourceTag: data kept locally until KRZ HRMS provides it. */
export function SourceTag() {
  return <Tag title="Managed in KRZ Attend now; will come from KRZ HRMS later">Local · HRMS later</Tag>;
}

/** The prototype's hrmsBanner: this data moves to KRZ HRMS once the integration is ready. */
export function HrmsBanner({ text = 'Managed in KRZ Attend for now. Will sync from KRZ HRMS.' }: { text?: string }) {
  return (
    <Callout tone="neutral" icon="sync" className="mb-16">
      <div className="small">
        <b>{text}</b> When the HRMS integration is ready, this data will be fetched from KRZ HRMS and become read-only
        here.
      </div>
    </Callout>
  );
}
