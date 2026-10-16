/**
 * The prototype's evacuation list: who is inside according to the cameras, with a print view and the CSV (F10: the
 * one client-side print view). Opening it is audited by the server.
 */
import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { apiUrl } from '@/api/client';
import { useEvacuation, type Evacuation } from '@/api/attendance';
import { EmptyState } from '@/components/feedback/EmptyState';
import { Modal } from '@/components/feedback/Modal';
import { toast } from '@/components/feedback/toast';
import { Button, DownloadButton } from '@/components/ui/Button';
import { Callout } from '@/components/ui/Callout';
import { Spinner } from '@/components/ui/Spinner';
import { DASH, exportFileName, fmtDateTime, fmtTime, zoneLabel } from '@/lib/format';
import { useCan, useNeedsPermissionText } from '@/lib/permissions';
import { plural } from '@/lib/text';
import '@/components/data/DataTable.module.css';

function Header({ e }: { e: Evacuation }) {
  return (
    <p className="small">
      {e.organisation} · {e.cameras[0] ?? ''} · generated {fmtDateTime(e.generatedAt)} {zoneLabel()} by {e.generatedBy}
    </p>
  );
}

function PeopleTable({ e }: { e: Evacuation }) {
  return (
    <table className="dt">
      <thead>
        <tr>
          <th>#</th>
          <th>Name</th>
          <th>Employee ID</th>
          <th>Department</th>
          <th>Last IN</th>
          <th>Accounted for</th>
        </tr>
      </thead>
      <tbody>
        {e.people.map(p => (
          <tr key={p.number}>
            <td className="num">{p.number}</td>
            <td>{p.name}</td>
            <td className="mono">{p.employeeId ?? DASH}</td>
            <td>{p.department ?? DASH}</td>
            <td className="num">{fmtTime(p.lastIn)}</td>
            <td>☐</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** The print view: #print-area is shown only while printing (print.css). */
function PrintArea({ e }: { e: Evacuation }) {
  return createPortal(
    <div id="print-area">
      <h1>Evacuation list</h1>
      <Header e={e} />
      <PeopleTable e={e} />
    </div>,
    document.body,
  );
}

export function EvacuationDialog({ onClose }: { onClose: () => void }) {
  const evacuation = useEvacuation();
  const canExport = useCan('events.export');
  const needs = useNeedsPermissionText();
  const [printing, setPrinting] = useState(false);
  const e = evacuation.data;

  useEffect(() => {
    if (!printing) return undefined;
    document.body.classList.add('printing');
    const done = (): void => {
      document.body.classList.remove('printing');
      setPrinting(false);
    };
    window.addEventListener('afterprint', done);
    const timer = setTimeout(() => {
      window.print();
    }, 50);
    return () => {
      clearTimeout(timer);
      window.removeEventListener('afterprint', done);
      document.body.classList.remove('printing');
    };
  }, [printing]);

  const inside = e?.people.length ?? 0;
  return (
    <Modal
      open
      size="lg"
      onClose={onClose}
      title={e ? `Evacuation list — ${plural(inside, 'person', 'people')} inside` : 'Evacuation list'}
      footer={
        <>
          {canExport ? (
            <DownloadButton
              icon="download"
              iconSize="default"
              href={apiUrl('/attendance/evacuation/export')}
              onClick={() => {
                toast(`Exported ${String(inside)} rows to ${exportFileName('evacuation-list')}`, {
                  type: 'success',
                  logged: true,
                });
              }}
            >
              Download CSV
            </DownloadButton>
          ) : (
            <Button
              icon="download"
              iconSize="default"
              onClick={() => {
                toast(needs('events.export'), { type: 'warn' });
              }}
            >
              Download CSV
            </Button>
          )}
          <Button
            variant="primary"
            icon="printer"
            iconSize="default"
            disabled={!e}
            onClick={() => {
              setPrinting(true);
            }}
          >
            Print
          </Button>
        </>
      }
    >
      {e ? (
        <>
          <Header e={e} />
          <Callout tone="warn" icon="alert" className="mb-12 small">
            Based on camera crossings only. People who entered without being recognised are not on this list
            {e.unidentifiedIns > 0 && (
              <>
                {' '}
                — <b>{plural(e.unidentifiedIns, 'unidentified crossing')}</b> today are still in the Review Queue
              </>
            )}
            . Visitors are tracked by reception.
          </Callout>
          {inside > 0 ? (
            <PeopleTable e={e} />
          ) : (
            <EmptyState
              icon="users"
              title="Nobody is inside"
              text="No tracked employee is currently inside according to the camera."
            />
          )}
          <PrintArea e={e} />
        </>
      ) : (
        <div className="grid-center" style={{ padding: 24 }}>
          <Spinner dark label="Loading the evacuation list" />
        </div>
      )}
    </Modal>
  );
}
