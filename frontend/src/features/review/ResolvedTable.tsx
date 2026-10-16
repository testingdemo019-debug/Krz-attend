/** The Review Queue's Resolved tab (the prototype's review-resolved dataTable), served page by page. */
import { useMemo } from 'react';
import { apiUrl } from '@/api/client';
import { useClosedReviewItems, useReviewFacets, type ReviewItem } from '@/api/review';
import { DataTable, type Column } from '@/components/data/DataTable';
import { useTableState, type SortState, type TableFilter } from '@/components/data/tableState';
import { CropImage } from '@/components/domain/CropImage';
import { exportFileName, fmtDateTime } from '@/lib/format';
import { useNow } from '@/lib/useNow';
import { REVIEW_OUTCOMES, REVIEW_TYPES, term } from '@/lib/vocabulary';

const DEFAULT_SORT: SortState = { key: 'resolvedAt', dir: 'desc' };

const COLUMNS: readonly Column<ReviewItem>[] = [
  {
    key: 'crop',
    label: 'Crop',
    render: r => {
      const s = r.sightings.find(x => x.code === r.bestSighting) ?? r.sightings[0];
      if (!s) return <CropImage crossing={r.code} available={false} />;
      const back = r.type === 'REID';
      return <CropImage crossing={s.code} kind={back ? 'body' : 'face'} available={back ? s.bodyCrop : s.faceCrop} />;
    },
  },
  {
    key: 'id',
    label: 'Item',
    sort: 'code',
    render: r => (
      <>
        <b className="mono">{r.code}</b>
        <div className="muted xs">
          {term(REVIEW_TYPES, r.type)?.short ?? r.typeLabel}
          {r.label ? ` · ${r.label}` : ''}
        </div>
      </>
    ),
  },
  {
    key: 'seen',
    label: 'Seen',
    sort: 'seen',
    render: r => <span className="num nowrap">{fmtDateTime(r.createdAt)}</span>,
  },
  { key: 'res', label: 'Resolution', sort: 'resolution', render: r => r.resolution?.label ?? '' },
  {
    key: 'who',
    label: 'Employee',
    render: r => r.resolution?.employee?.name ?? <span className="muted">—</span>,
  },
  { key: 'by', label: 'Resolved by', sort: 'resolvedBy', render: r => r.resolution?.by ?? '' },
  {
    key: 'when',
    label: 'Resolved at',
    sort: 'resolvedAt',
    render: r => <span className="num nowrap">{fmtDateTime(r.resolution?.at)}</span>,
  },
];

export function ResolvedTable() {
  const facets = useReviewFacets(true).data;
  const filters = useMemo<readonly TableFilter[]>(
    () => [
      {
        key: 'type',
        label: 'Type',
        options: Object.entries(REVIEW_TYPES).map(([value, t]) => ({
          value,
          label: t.short,
          count: facets?.type[value] ?? 0,
        })),
      },
      {
        key: 'resolution',
        label: 'Resolution',
        options: Object.entries(facets?.action ?? {}).map(([value, count]) => ({
          value,
          label: term(REVIEW_OUTCOMES, value) ?? value,
          count,
        })),
      },
      {
        key: 'by',
        label: 'Resolved by',
        options: (facets?.resolvedBy ?? []).map(r => ({ value: r.id ?? 'SYSTEM', label: r.name, count: r.count })),
      },
      { key: 'seen', label: 'Seen', range: true },
    ],
    [facets],
  );
  const control = useTableState(filters, DEFAULT_SORT);
  const { state } = control;
  const filterQuery = {
    q: state.q,
    type: state.values.type ?? [],
    action: state.values.resolution ?? [],
    resolvedBy: state.values.by ?? [],
    from: state.ranges.seen?.from ?? '',
    to: state.ranges.seen?.to ?? '',
    sort: state.sort ? `${state.sort.key},${state.sort.dir}` : null,
  };
  const result = useClosedReviewItems({ ...filterQuery, page: state.page - 1, size: state.size });
  const now = useNow(60_000);

  return (
    <DataTable
      id="review-resolved"
      control={control}
      filters={filters}
      columns={COLUMNS}
      rowKey={r => r.code}
      result={result}
      noun="resolved items"
      noun1="resolved item"
      empty={{ icon: 'history', title: 'No resolved items yet' }}
      exportCsv={{
        href: apiUrl('/review/export', filterQuery),
        fileName: exportFileName('review-history', now),
        permission: 'events.export',
      }}
    />
  );
}
