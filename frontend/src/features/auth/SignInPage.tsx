/**
 * /signin (F9): the prototype's first screen. The work e-mail is checked here (format and domain), again by the
 * server, then the browser goes to Microsoft (/oauth2/authorization/azure with login_hint and a validated returnTo).
 * Notices (?notice=) and sign-in failures (?error=) use the prototype's wording; the simulated Microsoft screens are
 * gone (F5).
 */
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useId, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Navigate, useSearchParams } from 'react-router';
import { safeReturnTo } from '@/api/client';
import { useBranding, useMe } from '@/api/me';
import { Icon } from '@/components/icons/Icon';
import { Button } from '@/components/ui/Button';
import { Callout, type CalloutTone } from '@/components/ui/Callout';
import { Spinner } from '@/components/ui/Spinner';
import type { IconName } from '@/components/icons/Icon';
import { usePageTitle } from '@/lib/usePageTitle';
import { signInSchema, type SignInInput, type SignInValues } from './schemas';
import { BrandHeader, SignInFrame } from './SignInFrame';
import '@/components/form/Field.module.css';

const NOTICES: Record<string, readonly [CalloutTone, IconName, string]> = {
  signed_out: ['ok', 'checkCircle', 'You’ve signed out.'],
  expired: ['warn', 'clock', 'Your session expired after a period without activity. Sign in again to continue.'],
  access_removed: ['warn', 'lock', 'Your access to KRZ Attend was removed by an administrator.'],
};

/** Server-side sign-in failures (LoginHandlers): the message under the e-mail field. */
function errorText(code: string | null, domain: string): string {
  switch (code) {
    case 'wrong_domain':
    case 'wrong_tenant':
      return `Use your KRZ Technologies work account (name@${domain}).`;
    case 'guest':
      return `Guest accounts can’t sign in to KRZ Attend. Use your KRZ Technologies work account (name@${domain}).`;
    case 'generic':
      return 'Sign-in didn’t complete. Try again.';
    default:
      return '';
  }
}

export function SignInPage() {
  usePageTitle('Sign in');
  const [params] = useSearchParams();
  const me = useMe();
  const domain = useBranding().data?.allowedDomain ?? '';
  // The e-mail Microsoft is asked to sign in, while the browser goes there.
  const [redirecting, setRedirecting] = useState<string | null>(null);
  const fieldId = useId();
  const returnTo = safeReturnTo(params.get('returnTo'));
  const notice = NOTICES[params.get('notice') ?? ''];
  const failure = params.get('error');
  const {
    register,
    handleSubmit,
    setFocus,
    formState: { errors },
  } = useForm<SignInInput, unknown, SignInValues>({
    resolver: zodResolver(signInSchema(domain)),
    reValidateMode: 'onSubmit',
    defaultValues: { email: '' },
  });

  useEffect(() => {
    if (redirecting === null) setFocus('email');
  }, [redirecting, setFocus]);

  if (me.data?.accessState === 'ACTIVE') return <Navigate to={returnTo ?? '/dashboard'} replace />;
  if (me.data) return <Navigate to="/access" replace />;
  if (failure === 'not_in_scope') return <NotSetUp />;

  return (
    <SignInFrame live={redirecting !== null}>
      {branding => {
        const message = errors.email?.message ?? errorText(failure, domain);
        const submit = handleSubmit(({ email }) => {
          setRedirecting(email);
          const query = new URLSearchParams({ login_hint: email });
          if (returnTo) query.set('returnTo', returnTo);
          window.location.assign(`/oauth2/authorization/azure?${query.toString()}`);
        });
        if (redirecting !== null) {
          return (
            <>
              <BrandHeader branding={branding} />
              <div className="row gap-12" style={{ padding: '18px 0' }}>
                <Spinner dark />
                <div>
                  <b id="si-title">Taking you to your organisation’s sign-in…</b>
                  <div className="muted small mt-4">Microsoft work account</div>
                </div>
              </div>
              <button type="button" className="email-chip" disabled aria-label="Account">
                <Icon name="user" size="sm" />
                <span>{redirecting}</span>
              </button>{' '}
              <a
                href="/signin"
                className="small"
                style={{ marginLeft: 6 }}
                onClick={event => {
                  event.preventDefault();
                  setRedirecting(null);
                }}
              >
                Not you?
              </a>
            </>
          );
        }
        return (
          <>
            <BrandHeader branding={branding} />
            {notice && (
              <Callout tone={notice[0]} icon={notice[1]} role="status" className="mb-16">
                {notice[2]}
              </Callout>
            )}
            <h1 id="si-title" style={{ fontSize: 20 }}>
              Sign in
            </h1>
            <p className="muted small mt-4 mb-16">Use your KRZ Technologies work account.</p>
            <form noValidate onSubmit={event => void submit(event)}>
              <div className="field">
                <label htmlFor={`${fieldId}-email`}>Work email</label>
                <input
                  id={`${fieldId}-email`}
                  className="input input-lg"
                  type="email"
                  autoComplete="username"
                  spellCheck={false}
                  placeholder={`name@${domain}`}
                  aria-describedby={`${fieldId}-err`}
                  aria-invalid={message ? true : undefined}
                  {...register('email')}
                />
                <div id={`${fieldId}-err`} className="err-text" role="alert">
                  {message}
                </div>
              </div>
              <Button type="submit" variant="primary" size="lg" className="mt-12" style={{ width: '100%' }}>
                Continue
              </Button>
            </form>
            <p className="small mt-24 si-help">
              Trouble signing in? Contact IT
              {branding?.helpdeskEmail ? (
                <>
                  {' '}
                  (<a href={`mailto:${branding.helpdeskEmail}`}>{branding.helpdeskEmail}</a>)
                </>
              ) : (
                '.'
              )}
            </p>
          </>
        );
      }}
    </SignInFrame>
  );
}

/** Microsoft accepted the account, but the directory sync has not brought it into KRZ Attend (not_in_scope). */
function NotSetUp() {
  return (
    <SignInFrame wide>
      {branding => (
        <>
          <BrandHeader branding={branding} />
          <div className="empty-ic round-ic amber">
            <Icon name="userX" size="lg" />
          </div>
          <h1 id="si-title" style={{ fontSize: 20 }}>
            Your account isn’t set up in KRZ Attend yet
          </h1>
          <p className="mt-8">
            You signed in with Microsoft, but your account hasn’t been synced from Microsoft Entra ID. Ask IT
            {branding?.helpdeskEmail ? ` (${branding.helpdeskEmail})` : ''}.
          </p>
          <Callout tone="neutral" className="mt-16">
            <span className="small">
              Accounts are synced regularly from Microsoft Entra ID. New joiners appear after IT adds them to the sync
              scope.
            </span>
          </Callout>
          <div className="row mt-16">
            <a className="btn" href="/signin">
              <Icon name="login" />
              Sign in again
            </a>
          </div>
        </>
      )}
    </SignInFrame>
  );
}
