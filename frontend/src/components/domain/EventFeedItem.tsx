import type { EventRow } from '@/api/live';
import { FeedItem } from '@/components/data/Feed';
import { fmtTime } from '@/lib/format';
import { usePermissionCheck } from '@/lib/permissions';
import { DirectionChip, EffectChip, methodLabel } from './Chips';
import { CropImage } from './CropImage';

/**
 * One crossing in a card list (the prototype's eventFeedItemHTML): crop, who, time with seconds · method, direction
 * and punch effect; a link to the event with events.view.
 */
export function EventFeedItem({ row }: { row: EventRow }) {
  const can = usePermissionCheck();
  const linked = can('events.view');
  return (
    <FeedItem to={linked ? `/events?id=${row.code}` : undefined}>
      <CropImage
        crossing={row.code}
        kind={row.faceCrop || !row.bodyCrop ? 'face' : 'body'}
        available={!row.cropsPurged && (row.faceCrop || row.bodyCrop)}
        revealable={!linked}
      />
      <span className="grow" style={{ minWidth: 0 }}>
        <b className="ellipsis" style={{ display: 'block' }}>
          {row.person?.name ?? row.unknownLabel ?? 'Unknown face'}
        </b>
        <span className="feed-meta">
          {fmtTime(row.occurredAt, true)} · {methodLabel(row.method, row.decision)}
        </span>
      </span>
      <DirectionChip direction={row.direction} /> <EffectChip effect={row.effect} />
    </FeedItem>
  );
}
