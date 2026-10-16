/**
 * The topbar search (the prototype's searchResults): pages from the navigation model (matched here, D-068), then
 * employees, events, review items, departments and shifts from GET /search, each group only with its permission.
 * "/" focuses it (AppShell); arrows move, Enter opens, Escape clears.
 */
import { Fragment, useDeferredValue, useEffect, useRef, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router';
import { useSearch } from '@/api/shell';
import { Icon } from '@/components/icons/Icon';
import { Avatar } from '@/components/ui/Avatar';
import { Chip } from '@/components/ui/Chip';
import { fmtDateTime } from '@/lib/format';
import type { NavSection } from '@/lib/nav';
import { useCan, useCanAny } from '@/lib/permissions';
import { REVIEW_STATUS, REVIEW_TYPES, term } from '@/lib/vocabulary';

interface Result {
  group: string;
  to: string;
  content: ReactNode;
}

export function GlobalSearch({
  sections,
  inputRef,
  onBlur,
}: {
  sections: readonly NavSection[];
  inputRef: React.RefObject<HTMLInputElement | null>;
  /** The field lost focus (the phone topbar closes its full-width search). */
  onBlur: () => void;
}) {
  const [q, setQ] = useState('');
  const [selected, setSelected] = useState(-1);
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();
  const people = useCanAny('people.view', 'people.enrol');
  const canProfile = useCan('people.view');
  const deferred = useDeferredValue(q);
  const server = useSearch(deferred).data;

  useEffect(() => {
    const close = (event: MouseEvent) => {
      if (!box.current?.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', close);
    return () => {
      document.removeEventListener('mousedown', close);
    };
  }, []);

  const text = q.trim().toLowerCase();
  const results: Result[] = [];
  if (text) {
    for (const item of sections.flatMap(s => s.items)) {
      if (item.label.toLowerCase().includes(text)) {
        results.push({
          group: 'Pages',
          to: item.to,
          content: (
            <>
              <Icon name={item.icon} />
              <span>{item.label}</span>
            </>
          ),
        });
      }
    }
    for (const e of server?.employees ?? []) {
      results.push({
        group: 'Employees',
        to: canProfile ? `/employees/${e.id}` : `/enrol?employee=${e.id}`,
        content: (
          <>
            <Avatar id={e.id} name={e.name} size="sm" />
            <span className="grow">
              <b>{e.name}</b> <span className="muted small">{e.employeeId ?? 'No Employee ID'}</span>
              <br />
              <span className="muted small">{e.sub}</span>
            </span>
            {!e.enabled && <Chip tone="grey">Disabled</Chip>}
          </>
        ),
      });
    }
    for (const ev of server?.events ?? []) {
      results.push({
        group: 'Events',
        to: `/events?id=${ev.code}`,
        content: (
          <>
            <Icon name="list" />
            <span className="grow">
              <b className="mono">{ev.code}</b> <span className="muted small">{fmtDateTime(ev.occurredAt)}</span>
              <br />
              <span className="muted small">
                {ev.person ?? 'Unknown'} · {ev.direction}
              </span>
            </span>
          </>
        ),
      });
    }
    for (const r of server?.reviewItems ?? []) {
      const status = term(REVIEW_STATUS, r.status) ?? r.status;
      results.push({
        group: 'Review items',
        to: `/review?item=${r.code}`,
        content: (
          <>
            <Icon name="review" />
            <span>
              <b className="mono">{r.code}</b> {r.label ?? term(REVIEW_TYPES, r.type)?.short ?? r.type}
              {r.status === 'OPEN' ? '' : ` · ${status}`}
            </span>
          </>
        ),
      });
    }
    for (const d of server?.departments ?? []) {
      results.push({
        group: 'Organisation',
        to: `/org/departments?id=${d.code}`,
        content: (
          <>
            <Icon name="building" />
            <span>
              {d.name} <span className="muted small">{d.code}</span>
            </span>
          </>
        ),
      });
    }
    for (const s of server?.shifts ?? []) {
      results.push({
        group: 'Organisation',
        to: `/org/shifts?id=${s.code}`,
        content: (
          <>
            <Icon name="timer" />
            <span>
              {s.name} shift <span className="muted small">{s.code}</span>
            </span>
          </>
        ),
      });
    }
  }

  const go = (to: string) => {
    setQ('');
    setOpen(false);
    setSelected(-1);
    if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
    void navigate(to);
  };

  let lastGroup = '';
  return (
    <div className="search" role="search" ref={box}>
      <span>
        <Icon name="search" />
      </span>
      <input
        ref={inputRef}
        id="global-search"
        type="search"
        placeholder={people ? 'Search employees, Employee IDs, events or pages' : 'Search pages'}
        aria-label="Global search"
        autoComplete="off"
        value={q}
        onChange={e => {
          setQ(e.target.value);
          setSelected(-1);
          setOpen(true);
        }}
        onFocus={() => {
          setOpen(true);
        }}
        onBlur={onBlur}
        onKeyDown={e => {
          if (e.key === 'ArrowDown') {
            e.preventDefault();
            setSelected(i => Math.min(results.length - 1, i + 1));
          } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            setSelected(i => Math.max(0, i - 1));
          } else if (e.key === 'Enter') {
            const target = results[Math.max(0, selected)];
            if (target) go(target.to);
          } else if (e.key === 'Escape') {
            setQ('');
            e.currentTarget.blur();
          }
        }}
      />
      <span className="kbd" aria-hidden="true">
        /
      </span>
      {open && text && (
        <div className="search-results" role="listbox" aria-label="Search results">
          {results.length ? (
            results.map((r, i) => {
              const header = r.group !== lastGroup ? <div className="sr-group">{r.group}</div> : null;
              lastGroup = r.group;
              return (
                <Fragment key={`${r.group}-${r.to}`}>
                  {header}
                  <a
                    className={i === selected ? 'sr-item sel' : 'sr-item'}
                    href={r.to}
                    role="option"
                    aria-selected={i === selected}
                    onMouseDown={e => {
                      e.preventDefault();
                    }}
                    onClick={e => {
                      e.preventDefault();
                      go(r.to);
                    }}
                  >
                    {r.content}
                  </a>
                </Fragment>
              );
            })
          ) : (
            <div className="muted small" style={{ padding: 12 }}>
              No results for “{q}”.
            </div>
          )}
        </div>
      )}
    </div>
  );
}
