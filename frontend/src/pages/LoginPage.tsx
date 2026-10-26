import { useState } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { CalendarCheck, Eye, EyeOff } from 'lucide-react';
import { ApiError } from '../api/client';
import { useAuth } from '../lib/auth';
import { Banner, Button, Field, Spinner } from '../components/ui';

export function LoginPage() {
  const { user, loading, login } = useAuth();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [show, setShow] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [touched, setTouched] = useState(false);

  if (loading) return <div className="center-screen"><Spinner size={28} /></div>;
  if (user) return <Navigate to={(location.state as { from?: string } | null)?.from ?? '/'} replace />;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setTouched(true);
    setError('');
    if (!email.trim() || !password) return;
    setBusy(true);
    try {
      await login(email.trim(), password);
    } catch (err) {
      if (err instanceof ApiError && err.code === 'INVALID_CREDENTIALS') setError('Incorrect email or password.');
      else if (err instanceof ApiError && err.code === 'TOO_MANY_ATTEMPTS') setError('Too many failed attempts. Please wait a few minutes and try again.');
      else setError(err instanceof ApiError ? err.message : 'Could not sign in. Please try again.');
      setBusy(false);
    }
  }

  return (
    <div className="center-screen">
      <form className="card login" onSubmit={submit} noValidate>
        <div className="login-brand"><span className="brand-mark big" aria-hidden><CalendarCheck size={26} /></span></div>
        <h1>Sign in</h1>
        <p className="muted">Book meeting rooms without the back-and-forth.</p>
        {error && <Banner tone="danger">{error}</Banner>}
        <Field label="Email" error={touched && !email.trim() ? 'Enter your email' : undefined}>
          <input type="email" autoComplete="username" autoFocus value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@company.com" />
        </Field>
        <Field label="Password" error={touched && !password ? 'Enter your password' : undefined}>
          <div className="input-group">
            <input type={show ? 'text' : 'password'} autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
            <button type="button" className="icon-btn" aria-label={show ? 'Hide password' : 'Show password'} onClick={() => setShow((s) => !s)}>
              {show ? <EyeOff size={18} /> : <Eye size={18} />}
            </button>
          </div>
        </Field>
        <Button variant="primary" type="submit" loading={busy} className="block">Sign in</Button>
        {import.meta.env.VITE_DEMO_HINT === 'true' && (
          <p className="demo-hint small muted">
            Demo: <code>admin@company.local</code> / <code>Admin12345!</code> · <code>alice@company.local</code> / <code>Password123!</code>
          </p>
        )}
      </form>
    </div>
  );
}
