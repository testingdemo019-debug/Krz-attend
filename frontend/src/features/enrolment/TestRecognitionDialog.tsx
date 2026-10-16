/**
 * The prototype's "Test recognition": the camera, then one frame checked against the gallery by the server
 * (nothing is stored; the run is audited). The new templates may take a few seconds to reach the gallery, so a miss
 * is tried again twice.
 */
import { useEffect, useRef, useState } from 'react';
import { testRecognition, type TestResult } from '@/api/enrolment';
import { Modal } from '@/components/feedback/Modal';
import { Icon } from '@/components/icons/Icon';
import { CONFIDENCE, term } from '@/lib/vocabulary';
import { CAMERA_ERRORS, frameJpeg, openCamera, stopCamera } from './camera';
import './Enrolment.module.css';

const ATTEMPTS = 3;

type Outcome = { kind: 'checking' | 'error'; text: string } | { kind: 'result'; result: TestResult };

function describe(result: TestResult): string {
  const band = term(CONFIDENCE, result.confidence)?.label ?? '';
  if (result.faces === 0) return 'No face found — look at the camera';
  if (result.faces > 1) return 'More than one face — only the enrolled person in view';
  if (result.matched) return `Matched: ${result.name ?? ''} · ${band}`;
  if (result.name) return `Recognised as ${result.name} · ${band}`;
  return 'Not recognised yet — try again in a few seconds';
}

export function TestRecognitionDialog({ employeeId, onClose }: { employeeId: string; onClose: () => void }) {
  const video = useRef<HTMLVideoElement>(null);
  const [outcome, setOutcome] = useState<Outcome>({ kind: 'checking', text: 'Starting camera…' });

  useEffect(() => {
    let stream: MediaStream | null = null;
    // Set by the cleanup while the checks run.
    const run = { stopped: false };
    const stopped = () => run.stopped;
    const wait = (ms: number) =>
      new Promise(resolve => {
        setTimeout(resolve, ms);
      });
    void (async () => {
      const opened = await openCamera('');
      if (stopped()) {
        if ('stream' in opened) stopCamera(opened.stream);
        return;
      }
      if ('error' in opened) {
        setOutcome({ kind: 'error', text: CAMERA_ERRORS[opened.error][1] });
        return;
      }
      stream = opened.stream;
      const v = video.current;
      if (!v) return;
      v.srcObject = stream;
      await v.play().catch(() => undefined);
      setOutcome({ kind: 'checking', text: 'Checking…' });
      for (let attempt = 1; attempt <= ATTEMPTS && !stopped(); attempt++) {
        await wait(1500);
        const frame = await frameJpeg(v);
        if (!frame || stopped()) continue;
        try {
          const result = await testRecognition(employeeId, frame);
          if (stopped()) return;
          if (result.matched || attempt === ATTEMPTS) {
            setOutcome({ kind: 'result', result });
            return;
          }
        } catch (e) {
          if (!stopped()) setOutcome({ kind: 'error', text: e instanceof Error ? e.message : 'The check failed' });
          return;
        }
      }
    })();
    return () => {
      run.stopped = true;
      stopCamera(stream);
    };
  }, [employeeId]);

  const matched = outcome.kind === 'result' && outcome.result.matched;
  return (
    <Modal open title="Test recognition" sub="Look at the camera. Nothing is recorded." onClose={onClose}>
      <div className="preview" style={{ aspectRatio: '4/3' }}>
        <video ref={video} className="mirror" playsInline muted />
        <div
          className="hint"
          role="status"
          aria-live="polite"
          style={matched ? { background: 'rgba(23,112,63,.9)' } : undefined}
        >
          {matched && (
            <>
              <Icon name="checkCircle" />{' '}
            </>
          )}
          {outcome.kind === 'result' ? describe(outcome.result) : outcome.text}
        </div>
      </div>
    </Modal>
  );
}
