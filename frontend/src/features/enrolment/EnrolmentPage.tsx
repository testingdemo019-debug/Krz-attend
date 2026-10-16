/**
 * Face enrolment (the prototype's #/enrol, R15): Select person → Consent → Method → Capture (live camera with
 * MediaPipe guidance, or photos) → Review with the duplicate check → Save. ?employee= opens a person's enrolment, as
 * the profile's "Enrol face" and "Enrol next person" do. The server session holds the captures; leaving the page or
 * starting over cancels it, which discards them.
 */
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useEffectEvent, useReducer, useRef, type ReactNode } from 'react';
import { useSearchParams } from 'react-router';
import { ApiError } from '@/api/client';
import {
  cancelSession,
  useActiveNotice,
  useCompleteEnrolment,
  useEnrolmentCandidate,
  useEnrolmentRules,
  useRecordConsent,
  useRemoveCapture,
  useStartSession,
  type EnrolmentMethod,
} from '@/api/enrolment';
import { queryKeys } from '@/api/queryKeys';
import { useConfirm } from '@/components/feedback/ConfirmDialog';
import { toast } from '@/components/feedback/toast';
import { PageHead } from '@/components/layout/PageHead';
import { Button } from '@/components/ui/Button';
import { Skeleton } from '@/components/ui/Skeleton';
import { Steps } from '@/components/ui/Steps';
import { useMeUser } from '@/lib/permissions';
import { ConsentStep } from './ConsentStep';
import { LiveCapture } from './LiveCapture';
import { MethodStep } from './MethodStep';
import { ReviewStep } from './ReviewStep';
import { EnrolSuccess, SaveStep } from './SaveStep';
import { lockedNow, SelectStep } from './SelectStep';
import { UploadStep } from './UploadStep';
import { goodCaptures, initialWizard, STEP_NAMES, wizardReducer } from './wizard';
import './Enrolment.module.css';

const GIVEN_METHODS = new Set(['WRITTEN_FORM', 'EMAIL_CONFIRMATION']);

export function EnrolmentPage() {
  const [params, setParams] = useSearchParams();
  const employeeParam = params.get('employee');
  const [state, dispatch] = useReducer(wizardReducer, employeeParam, initialWizard);
  const queryClient = useQueryClient();
  const me = useMeUser();
  const confirm = useConfirm();
  const candidateQuery = useEnrolmentCandidate(state.employeeId);
  const candidate = candidateQuery.data;
  const rules = useEnrolmentRules().data;
  const notice = useActiveNotice(state.employeeId !== null).data;
  const start = useStartSession();
  const recordConsent = useRecordConsent();
  const removeCapture = useRemoveCapture();
  const complete = useCompleteEnrolment();
  const openSession = useRef<string | null>(null);
  const startingFor = useRef<string | null>(null);
  const first = candidate ? (candidate.employee.name.split(' ')[0] ?? candidate.employee.name) : '';

  // The open session is cancelled when the page is left (its captures are discarded).
  useEffect(() => {
    openSession.current = state.session && !state.saved && state.session.status === 'OPEN' ? state.session.code : null;
  }, [state.session, state.saved]);
  useEffect(
    () => () => {
      if (openSession.current) void cancelSession(openSession.current).catch(() => undefined);
    },
    [],
  );

  /** A fresh wizard for someone else (or no one); the URL follows. */
  const restart = (employeeId: string | null, step?: number): void => {
    if (openSession.current) void cancelSession(openSession.current).catch(() => undefined);
    openSession.current = null;
    startingFor.current = null;
    dispatch({ type: 'restart', employeeId, ...(step === undefined ? {} : { step }) });
    setParams(employeeId ? { employee: employeeId } : {}, { replace: true });
  };

  // ?employee= changed from outside (a link while the page is open): that person's wizard.
  const onParam = useEffectEvent((param: string | null) => {
    if (param === state.employeeId) return;
    if (param || state.saved) restart(param);
  });
  useEffect(() => {
    onParam(employeeParam);
  }, [employeeParam]);

  // The consent draft and the template mode start from the person's record (the prototype's startEnrolWizard).
  const onCandidate = useEffectEvent(() => {
    if (!candidate || state.consentLoaded || candidate.employee.id !== state.employeeId) return;
    const c = candidate.consent;
    dispatch({
      type: 'consent',
      loaded: true,
      patch: c.onFile
        ? {
            confirmed: true,
            onFile: true,
            ...(c.method && GIVEN_METHODS.has(c.method) ? { method: c.method as 'WRITTEN_FORM' } : {}),
          }
        : {},
    });
    if (candidate.templates.length > 0) {
      dispatch({ type: 'mode', mode: candidate.faceStatus === 'ENROLLED' ? 'ADD' : 'REPLACE' });
    }
  });
  useEffect(() => {
    onCandidate();
  }, [candidate]);

  // From the consent step on, the person has an open session (a locked person goes back to step 1).
  const onNeedSession = useEffectEvent(() => {
    const id = state.employeeId;
    if (state.step < 2 || !id || state.session || state.saved || !candidate || startingFor.current === id) return;
    if (lockedNow(candidate.lockedUntil)) {
      dispatch({ type: 'go', step: 1 });
      return;
    }
    startingFor.current = id;
    start.mutate(
      { employeeId: id, method: state.method },
      {
        onSuccess: session => {
          dispatch({ type: 'session', session });
        },
        onError: error => {
          startingFor.current = null;
          toast(error.message, { type: error instanceof ApiError && error.status === 409 ? 'warn' : 'error' });
          dispatch({ type: 'go', step: 1 });
          void queryClient.invalidateQueries({ queryKey: queryKeys.enrolment });
        },
      },
    );
  });
  useEffect(() => {
    onNeedSession();
  }, [state.step, state.employeeId, state.session, candidate]);

  const go = (step: number): void => {
    dispatch({ type: 'go', step });
    window.scrollTo({ top: 0, behavior: 'smooth' });
  };

  const saveConsent = (): void => {
    if (!state.session || !notice) return;
    if (state.consent.onFile) {
      go(3);
      return;
    }
    recordConsent.mutate(
      {
        code: state.session.code,
        consent: {
          decision: 'GIVEN',
          method: state.consent.method,
          noticeVersion: notice.version,
          document: state.consent.document,
        },
      },
      {
        onSuccess: session => {
          dispatch({ type: 'session', session });
          go(3);
        },
        onError: error => toast(error.message, { type: 'error' }),
      },
    );
  };

  const decline = async (): Promise<void> => {
    if (!state.session || !notice) return;
    const answer = await confirm({
      title: `Record that ${first} declined?`,
      message: (
        <>
          No face images are captured. {first}’s attendance is <b>not tracked</b> by camera, and HR records it in KRZ
          HRMS instead.
        </>
      ),
      confirmText: 'Record and finish',
    });
    if (!answer) return;
    recordConsent.mutate(
      { code: state.session.code, consent: { decision: 'DECLINED', noticeVersion: notice.version } },
      {
        onSuccess: () => {
          openSession.current = null;
          toast(`Recorded — ${first} is not tracked by camera`, { type: 'success', logged: true });
          restart(null);
        },
        onError: error => toast(error.message, { type: 'error' }),
      },
    );
  };

  const chooseMethod = (): void => {
    const id = state.employeeId;
    if (!state.session || !id) return;
    if (state.session.method === state.method) {
      go(4);
      return;
    }
    // Another method is another session (consent stays on file).
    start.mutate(
      { employeeId: id, method: state.method },
      {
        onSuccess: session => {
          dispatch({ type: 'session', session });
          go(4);
        },
        onError: error => toast(error.message, { type: 'error' }),
      },
    );
  };

  const save = (): void => {
    if (!state.session) return;
    const good = goodCaptures(state);
    complete.mutate(
      {
        code: state.session.code,
        captureIds: good.map(c => c.id),
        lookalikeConfirmed: state.dupConfirm,
        mode: state.mode,
      },
      {
        onSuccess: completed => {
          openSession.current = null;
          dispatch({ type: 'saved', completed });
          window.scrollTo({ top: 0, behavior: 'smooth' });
          toast(`${candidate?.employee.name ?? 'Employee'} enrolled with ${String(completed.templates)} templates`, {
            type: 'success',
            logged: true,
          });
        },
        onError: error => toast(error.message, { type: 'error' }),
      },
    );
  };

  const session = state.session;
  let body: ReactNode;
  if (state.saved && candidate) {
    body = (
      <EnrolSuccess
        candidate={candidate}
        saved={state.saved}
        recordedBy={candidate.consent.recordedBy ?? me.name}
        onNext={id => {
          restart(id);
        }}
      />
    );
  } else if (state.step === 1) {
    body = (
      <SelectStep
        employeeId={state.employeeId}
        q={state.q}
        onSearch={q => {
          dispatch({ type: 'search', q });
        }}
        onPick={id => {
          restart(id, 1);
        }}
        onNext={() => {
          go(2);
        }}
      />
    );
  } else if (!candidate || !rules || !notice || !session) {
    body = <Skeleton className="sk-line" width="40%" />;
  } else if (state.step === 2) {
    body = (
      <ConsentStep
        candidate={candidate}
        notice={notice}
        draft={state.consent}
        onDraft={patch => {
          dispatch({ type: 'consent', patch });
        }}
        onDecline={() => void decline()}
        onBack={() => {
          go(1);
        }}
        onNext={saveConsent}
        pending={recordConsent.isPending}
      />
    );
  } else if (state.step === 3) {
    body = (
      <MethodStep
        candidate={candidate}
        method={state.method}
        mode={state.mode}
        allowUpload={rules.allowUpload}
        onMethod={(method: EnrolmentMethod) => {
          dispatch({ type: 'method', method });
        }}
        onMode={mode => {
          dispatch({ type: 'mode', mode });
        }}
        onBack={() => {
          go(2);
        }}
        onNext={chooseMethod}
        pending={start.isPending}
      />
    );
  } else if (state.step === 4) {
    body =
      session.method === 'LIVE' ? (
        <LiveCapture
          candidate={candidate}
          session={session}
          rules={rules}
          poses={state.poses}
          images={state.images}
          guidance={state.guidance}
          livenessPassed={state.livenessPassed}
          onCaptured={(result, image, pose) => {
            dispatch({ type: 'captured', result, image, pose });
          }}
          onRemoved={captureId => {
            dispatch({ type: 'removed', captureId });
          }}
          onGuidance={guidance => {
            dispatch({ type: 'guidance', guidance });
          }}
          onLiveness={passed => {
            dispatch({ type: 'liveness', passed });
          }}
          onUnlocked={() => {
            dispatch({ type: 'session', session: { ...session, lockedUntil: null, livenessFailures: 0 } });
          }}
          onBack={() => {
            go(3);
          }}
          onNext={() => {
            go(5);
          }}
        />
      ) : (
        <UploadStep
          candidate={candidate}
          session={session}
          rules={rules}
          images={state.images}
          names={state.names}
          onCaptured={(result, image, name) => {
            dispatch({ type: 'captured', result, image, name });
          }}
          onRemoved={captureId => {
            dispatch({ type: 'removed', captureId });
          }}
          onBack={() => {
            go(3);
          }}
          onNext={() => {
            go(5);
          }}
        />
      );
  } else if (state.step === 5) {
    body = (
      <ReviewStep
        candidate={candidate}
        code={session.code}
        rules={rules}
        captures={goodCaptures(state)}
        images={state.images}
        names={state.names}
        poses={state.poses}
        live={session.method === 'LIVE'}
        dupConfirm={state.dupConfirm}
        onDupConfirm={value => {
          dispatch({ type: 'dupConfirm', value });
        }}
        onRemoved={captureId => {
          dispatch({ type: 'removed', captureId });
        }}
        onRetake={captureId => {
          removeCapture.mutate(
            { code: session.code, captureId },
            {
              onSuccess: () => {
                dispatch({ type: 'removed', captureId });
                dispatch({ type: 'liveness', passed: false });
                go(4);
              },
              onError: error => toast(error.message, { type: 'error' }),
            },
          );
        }}
        onBack={() => {
          go(4);
        }}
        onNext={() => {
          go(6);
        }}
      />
    );
  } else {
    body = (
      <SaveStep
        candidate={candidate}
        notice={notice}
        rules={rules}
        consent={state.consent}
        good={goodCaptures(state).length}
        method={session.method === 'UPLOAD' ? 'UPLOAD' : 'LIVE'}
        mode={state.mode}
        guidance={state.guidance}
        pending={complete.isPending}
        onBack={() => {
          go(5);
        }}
        onSave={save}
      />
    );
  }

  return (
    <>
      <PageHead
        title="Face enrolment"
        sub="Guided capture with live quality checks. Works on a desktop webcam or a tablet at reception."
        // The prototype always has the actions box (empty until a person is chosen): it spaces the head on phones.
        actions={
          state.employeeId && !state.saved ? (
            <Button
              onClick={() => {
                restart(null);
              }}
            >
              Start over
            </Button>
          ) : null
        }
      />
      <div className="wiz">
        <Steps
          className="stepper"
          label="Enrolment steps"
          names={STEP_NAMES}
          step={state.step}
          allDone={state.saved !== null}
        />
        <div className="card pad" style={{ minWidth: 0 }}>
          {body}
        </div>
      </div>
    </>
  );
}
