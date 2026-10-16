/**
 * The Review Queue (the prototype's #/review): a tab per item type with its open count plus Resolved; filters by
 * direction, confidence, date and waiting time; the cards oldest first with bulk selection, J/K to move and C A V R
 * to act; ?tab= and ?item= as in the prototype. Filters live in the URL (F10).
 */
import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router';
import { useMeta } from '@/api/me';
import { useOpenReviewItems, useReviewItem, type ReviewItem } from '@/api/review';
import { useReviewCounts } from '@/api/shell';
import { useShell } from '@/app/shell';
import { EmptyState, ErrorState } from '@/components/feedback/EmptyState';
import { DateField } from '@/components/form/DateField';
import { Switch } from '@/components/form/Switch';
import { Card } from '@/components/layout/Card';
import { PageHead } from '@/components/layout/PageHead';
import { Button } from '@/components/ui/Button';
import { Kbd } from '@/components/ui/Kbd';
import { SkeletonRows } from '@/components/ui/Skeleton';
import { Tabs, type TabItem } from '@/components/ui/Tabs';
import { isTyping, overlayOpen } from '@/lib/keyboard';
import { readPreference, writePreference } from '@/lib/storage';
import { useNow } from '@/lib/useNow';
import { REVIEW_TYPES, type ReviewType } from '@/lib/vocabulary';
import { CropGallery } from './CropGallery';
import { ResolvedTable } from './ResolvedTable';
import { ReviewCard } from './ReviewCard';
import { BULK_LABELS, useReviewDecisions, type BulkOp, type CardChoice } from './useReviewDecisions';
import '@/components/form/Check.module.css';
import './ReviewQueue.module.css';

type TabKey = 'unknown' | 'uncertain' | 'reid' | 'liveness' | 'resolved';
const TAB_KEYS: readonly TabKey[] = ['unknown', 'uncertain', 'reid', 'liveness', 'resolved'];
/** The open-item filters in the URL; switching between open tabs keeps them. */
const OPEN_FILTERS = ['direction', 'band', 'from', 'to', 'waiting'] as const;
const NO_CHOICE: CardChoice = { best: null, addTemplate: false };

const typeOfTab = (tab: TabKey): ReviewType => tab.toUpperCase() as ReviewType;
const isTab = (value: string | null): value is TabKey => TAB_KEYS.includes(value as TabKey);

export function ReviewQueuePage() {
  const [params, setParams] = useSearchParams();
  const { showShortcuts } = useShell();
  const itemParam = params.get('item');
  const linked = useReviewItem(itemParam).data;
  const tabParam = params.get('tab');
  const tab: TabKey = linked
    ? linked.status === 'OPEN'
      ? (linked.type.toLowerCase() as TabKey)
      : 'resolved'
    : isTab(tabParam)
      ? tabParam
      : readPreference<TabKey>('review-tab', 'unknown');
  const counts = useReviewCounts(true).data;

  const selectTab = (next: TabKey): void => {
    writePreference('review-tab', next);
    setParams(
      prev => {
        const out = new URLSearchParams({ tab: next });
        if (next !== 'resolved') {
          for (const k of OPEN_FILTERS) {
            for (const v of prev.getAll(k)) out.append(k, v);
          }
        }
        return out;
      },
      { replace: true },
    );
  };

  const tabs = useMemo<readonly TabItem<TabKey>[]>(
    () => [
      ...Object.entries(REVIEW_TYPES).map(([type, t]) => ({
        key: type.toLowerCase() as TabKey,
        label: t.label,
        icon: t.icon,
        count: counts?.open[type] ?? 0,
      })),
      { key: 'resolved', label: 'Resolved', icon: 'history', count: counts?.resolved ?? 0 },
    ],
    [counts],
  );

  return (
    <>
      <PageHead
        title="Review Queue"
        sub="Every crossing the system wasn’t sure about. Decisions go through the punch rules; nothing is added to the face gallery unless you tick “add as template”."
        actions={
          <Button icon="keyboard" iconSize="default" onClick={showShortcuts}>
            Shortcuts <Kbd>?</Kbd>
          </Button>
        }
      />
      <Tabs label="Review item types" items={tabs} active={tab} onSelect={selectTab} />
      {tab === 'resolved' ? (
        <ResolvedTable />
      ) : (
        <OpenItems
          key={tab}
          type={typeOfTab(tab)}
          openCount={counts?.open[typeOfTab(tab)] ?? 0}
          focusCode={linked?.status === 'OPEN' ? linked.code : null}
        />
      )}
    </>
  );
}

function OpenFilters({ type }: { type: ReviewType }) {
  const [params, setParams] = useSearchParams();
  const set = (key: (typeof OPEN_FILTERS)[number], value: string): void => {
    setParams(
      prev => {
        const out = new URLSearchParams(prev);
        out.delete('item');
        if (value) out.set(key, value);
        else out.delete(key);
        return out;
      },
      { replace: true },
    );
  };
  const any = OPEN_FILTERS.some(k => params.has(k));
  return (
    <div className="row wrap gap-8 mb-12">
      <select
        className="select"
        style={{ width: 150 }}
        aria-label="Direction"
        value={params.get('direction') ?? ''}
        onChange={event => {
          set('direction', event.target.value);
        }}
      >
        <option value="">Any direction</option>
        <option>IN</option>
        <option>OUT</option>
      </select>
      {type !== 'UNKNOWN' && (
        <select
          className="select"
          style={{ width: 170 }}
          aria-label="Confidence"
          value={params.get('band') ?? ''}
          onChange={event => {
            set('band', event.target.value);
          }}
        >
          <option value="">Any confidence</option>
          <option value="HIGH">High</option>
          <option value="MEDIUM">Medium</option>
          <option value="LOW">Low</option>
        </select>
      )}
      <span style={{ width: 160 }}>
        <DateField
          value={params.get('from') ?? ''}
          label="From date"
          placeholder="From date"
          onChange={day => {
            set('from', day);
          }}
        />
      </span>
      <span className="muted">–</span>
      <span style={{ width: 160 }}>
        <DateField
          value={params.get('to') ?? ''}
          label="To date"
          placeholder="To date"
          onChange={day => {
            set('to', day);
          }}
        />
      </span>
      <Switch
        id="review-old"
        label="Waiting over 30 min"
        checked={params.get('waiting') === '1'}
        onChange={event => {
          set('waiting', event.target.checked ? '1' : '');
        }}
      />
      {any && (
        <Button
          variant="ghost"
          size="sm"
          onClick={() => {
            setParams(prev => new URLSearchParams({ tab: prev.get('tab') ?? '' }), { replace: true });
          }}
        >
          Clear filters
        </Button>
      )}
    </div>
  );
}

function OpenItems({ type, openCount, focusCode }: { type: ReviewType; openCount: number; focusCode: string | null }) {
  const [params, setParams] = useSearchParams();
  const list = useOpenReviewItems({
    type,
    direction: params.get('direction') ?? '',
    band: params.get('band') ?? '',
    from: params.get('from') ?? '',
    to: params.get('to') ?? '',
    waiting: params.get('waiting') === '1',
  });
  const items = useMemo(() => list.data?.items ?? [], [list.data]);
  const unknownHours = useMeta(true).data?.unknownFaceHours ?? 24;
  const now = useNow(60_000);
  const { actions, leaving, dialogs } = useReviewDecisions();
  const [cursor, setCursor] = useState<{ code: string | null; index: number }>({ code: focusCode, index: 0 });
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const [choices, setChoices] = useState<Readonly<Record<string, CardChoice>>>({});
  const [gallery, setGallery] = useState<ReviewItem | null>(null);

  // The current card: the one the cursor names, else the same position (the next item after a decision).
  const found = cursor.code === null ? -1 : items.findIndex(i => i.code === cursor.code);
  const index = found >= 0 ? found : Math.min(cursor.index, Math.max(0, items.length - 1));
  const current = items[index];
  const choiceOf = (code: string): CardChoice => choices[code] ?? NO_CHOICE;

  // A ?item= link scrolls to its card once.
  useEffect(() => {
    if (!focusCode || !items.some(i => i.code === focusCode)) return;
    document.querySelector(`[data-review="${focusCode}"]`)?.scrollIntoView({ block: 'center' });
  }, [focusCode, items]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent): void => {
      if (isTyping(event) || overlayOpen() || !current) return;
      const k = event.key.toLowerCase();
      const move = (d: number): void => {
        const next = Math.min(Math.max(index + d, 0), items.length - 1);
        const item = items[next];
        if (!item) return;
        setCursor({ code: item.code, index: next });
        document.querySelector(`[data-review="${item.code}"]`)?.scrollIntoView({ block: 'center' });
      };
      const choice = choices[current.code] ?? NO_CHOICE;
      if (k === 'j') move(1);
      else if (k === 'k') move(-1);
      else if (k === 'c' && current.type !== 'UNKNOWN') actions.confirm(current, choice);
      else if (k === 'a') actions.assign(current, choice);
      else if (k === 'v' && (current.type === 'UNKNOWN' || current.type === 'UNCERTAIN'))
        void actions.notEmployee(current);
      else if (k === 'r') actions.reject(current);
      else return;
      event.preventDefault();
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
    };
  }, [actions, choices, current, index, items]);

  if (list.isPending) {
    return (
      <Card>
        <SkeletonRows rows={4} columns={3} />
      </Card>
    );
  }
  if (list.isError) {
    return (
      <>
        <OpenFilters type={type} />
        <Card>
          <ErrorState
            what="the Review Queue"
            onRetry={() => {
              void list.refetch();
            }}
          />
        </Card>
      </>
    );
  }

  const label = REVIEW_TYPES[type].label;
  const chosen = items.filter(i => selected.has(i.code));
  const allChosen = chosen.length > 0 && chosen.length === items.length;
  const bulkOps: readonly BulkOp[] = [
    ...(type === 'UNKNOWN' || type === 'UNCERTAIN' ? (['NOT_EMPLOYEE'] as const) : []),
    ...(type === 'UNCERTAIN' || type === 'REID' ? (['CONFIRM'] as const) : []),
    'REJECT',
  ];

  return (
    <>
      <OpenFilters type={type} />
      {items.length === 0 ? (
        <Card>
          <EmptyState
            icon="checkCircle"
            title="All caught up"
            text={
              openCount
                ? 'No items match these filters.'
                : `No ${label.toLowerCase()} waiting. New items appear here automatically.`
            }
            {...(openCount
              ? {
                  action: (
                    <Button
                      onClick={() => {
                        setParams(prev => new URLSearchParams({ tab: prev.get('tab') ?? '' }), { replace: true });
                      }}
                    >
                      Clear filters
                    </Button>
                  ),
                }
              : {})}
          />
        </Card>
      ) : (
        <>
          <div className="row between mb-8 wrap gap-8">
            <label className="check" style={{ alignItems: 'center' }}>
              <input
                type="checkbox"
                checked={allChosen}
                onChange={() => {
                  setSelected(allChosen ? new Set() : new Set(items.map(i => i.code)));
                }}
              />{' '}
              Select all ({items.length})
            </label>
            {chosen.length > 0 ? (
              <div className="row gap-8 wrap">
                <span className="small">
                  <b>{chosen.length}</b> selected
                </span>
                {bulkOps.map(op => (
                  <Button
                    key={op}
                    size="sm"
                    onClick={() => {
                      void actions.bulk(op, chosen, () => {
                        setSelected(new Set());
                      });
                    }}
                  >
                    {BULK_LABELS[op]}
                  </Button>
                ))}
              </div>
            ) : (
              <span className="muted small">Oldest first · J / K to move · C A V R to act</span>
            )}
          </div>
          <div className="rv-layout">
            {items.map(item => (
              <ReviewCard
                key={item.code}
                item={item}
                current={item.code === current?.code}
                leaving={leaving.has(item.code)}
                selected={selected.has(item.code)}
                onSelect={() => {
                  setSelected(prev => {
                    const next = new Set(prev);
                    if (next.has(item.code)) next.delete(item.code);
                    else next.add(item.code);
                    return next;
                  });
                }}
                choice={choiceOf(item.code)}
                onChoice={choice => {
                  setChoices(prev => ({ ...prev, [item.code]: choice }));
                }}
                actions={actions}
                onGallery={() => {
                  setGallery(item);
                }}
                unknownHours={unknownHours}
                now={now}
              />
            ))}
          </div>
        </>
      )}
      {gallery && (
        <CropGallery
          item={gallery}
          onClose={() => {
            setGallery(null);
          }}
          onPickBest={code => {
            setChoices(prev => ({ ...prev, [gallery.code]: { ...(prev[gallery.code] ?? NO_CHOICE), best: code } }));
          }}
        />
      )}
      {dialogs}
    </>
  );
}
