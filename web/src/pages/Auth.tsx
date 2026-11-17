import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { ApiError } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { PageTitle } from '../components/bits';
import { GeneratedPoster } from '../components/Poster';
import { useToast } from '../components/Toasts';

/** Only same-site paths are followed after sign-in, so ?next cannot redirect off the site. */
export function safeNext(next: string | null): string {
  return next && next.startsWith('/') && !next.startsWith('//') ? next : '/';
}

export function Login() {
  return <AuthForm mode="login" />;
}

export function Register() {
  return <AuthForm mode="register" />;
}

function AuthForm({ mode }: { mode: 'login' | 'register' }) {
  const { client } = useAuth();
  const toast = useToast();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const next = safeNext(params.get('next'));
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<{ msg: string; ref?: string } | null>(null);
  const register = mode === 'register';

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    const email = String(f.get('email') ?? '').trim();
    const password = String(f.get('password') ?? '');
    const name = String(f.get('name') ?? '').trim();
    const local: Record<string, string> = {};
    if (register && !name) local.name = 'Enter your name.';
    if (!/^\S+@\S+\.\S+$/.test(email)) local.email = 'Enter an email address like name@example.com.';
    if (register && password.length < 10) local.password = 'Use at least 10 characters.';
    if (!register && !password) local.password = 'Enter your password.';
    setErrors(local);
    setFormError(null);
    if (Object.keys(local).length) return;
    setBusy(true);
    try {
      const u = register ? await client.register(name, email, password) : await client.login(email, password);
      toast.show(register ? `Welcome, ${u.name}.` : `Signed in as ${u.name}.`, 'success');
      navigate(next, { replace: true });
    } catch (err) {
      setBusy(false);
      if (err instanceof ApiError) {
        setErrors(err.fieldErrors);
        const msg =
          err.status === 401
            ? 'That email and password do not match an account.'
            : err.status === 409
              ? 'An account with that email already exists. Sign in instead.'
              : err.message;
        setFormError({ msg, ref: err.requestId });
      } else {
        setFormError({ msg: 'Something went wrong. Try again.' });
      }
    }
  }

  const field = (name: string, label: string, type: string, autoComplete: string, hint?: string) => (
    <div className="field">
      <label htmlFor={`f-${name}`}>{label}</label>
      <input
        id={`f-${name}`}
        name={name}
        type={type}
        autoComplete={autoComplete}
        aria-invalid={errors[name] ? true : undefined}
        aria-describedby={[hint && `f-${name}-hint`, errors[name] && `f-${name}-err`].filter(Boolean).join(' ') || undefined}
      />
      {hint && (
        <p className="field__hint" id={`f-${name}-hint`}>
          {hint}
        </p>
      )}
      {errors[name] && (
        <p className="field-error" id={`f-${name}-err`}>
          {errors[name]}
        </p>
      )}
    </div>
  );

  return (
    <div className="wrap auth">
      <div className="auth__art" aria-hidden="true">
        <GeneratedPoster title={register ? 'Your Seat Is Waiting' : 'Welcome Back'} />
      </div>
      <div className="auth__panel">
        <PageTitle>{register ? 'Create your account' : 'Sign in'}</PageTitle>
        <p className="lede">{register ? 'Book seats, keep your tickets and cancel with a refund.' : 'Pick up where you left off.'}</p>
        <form noValidate onSubmit={submit} className="form">
          {formError && (
            <div className="alert" role="alert">
              <p>{formError.msg}</p>
              {formError.ref && <p className="state__ref">Reference {formError.ref}</p>}
            </div>
          )}
          {register && field('name', 'Name', 'text', 'name')}
          {field('email', 'Email', 'email', 'email')}
          {field('password', 'Password', 'password', register ? 'new-password' : 'current-password', register ? 'At least 10 characters.' : undefined)}
          <button type="submit" className="btn btn--lg btn--block" disabled={busy}>
            {busy ? (register ? 'Creating account' : 'Signing in') : register ? 'Create account' : 'Sign in'}
          </button>
        </form>
        <p className="auth__switch">
          {register ? 'Already have an account? ' : 'New here? '}
          <Link to={`${register ? '/login' : '/register'}${params.get('next') ? `?next=${encodeURIComponent(next)}` : ''}`}>
            {register ? 'Sign in' : 'Create an account'}
          </Link>
        </p>
      </div>
    </div>
  );
}
