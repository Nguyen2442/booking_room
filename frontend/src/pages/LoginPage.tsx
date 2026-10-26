import { useState } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { CalendarCheck, Eye, EyeOff } from 'lucide-react';
import { useAuth } from '../lib/auth';
import { LanguageSwitch, useI18n } from '../lib/i18n';
import { Banner, Button, Field, Spinner } from '../components/ui';

export function LoginPage() {
  const { user, loading, login } = useAuth();
  const { t, err } = useI18n();
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
    } catch (e2) {
      setError(err(e2));
      setBusy(false);
    }
  }

  return (
    <div className="center-screen">
      <div className="login-lang"><LanguageSwitch /></div>
      <form className="card login" onSubmit={submit} noValidate>
        <div className="login-brand"><span className="brand-mark big" aria-hidden><CalendarCheck size={26} /></span></div>
        <h1>{t('login.title')}</h1>
        <p className="muted">{t('login.subtitle')}</p>
        {error && <Banner tone="danger">{error}</Banner>}
        <Field label={t('login.email')} error={touched && !email.trim() ? t('login.enterEmail') : undefined}>
          <input type="email" autoComplete="username" autoFocus value={email} onChange={(e) => setEmail(e.target.value)} placeholder={t('login.emailPh')} />
        </Field>
        <Field label={t('login.password')} error={touched && !password ? t('login.enterPassword') : undefined}>
          <div className="input-group">
            <input type={show ? 'text' : 'password'} autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
            <button type="button" className="icon-btn" aria-label={show ? t('login.hide') : t('login.show')} onClick={() => setShow((s) => !s)}>
              {show ? <EyeOff size={18} /> : <Eye size={18} />}
            </button>
          </div>
        </Field>
        <Button variant="primary" type="submit" loading={busy} className="block">{t('login.submit')}</Button>
        {import.meta.env.VITE_DEMO_HINT === 'true' && (
          <p className="demo-hint small muted">
            {t('login.demo')} <code>admin@company.local</code> / <code>Admin12345!</code> · <code>alice@company.local</code> / <code>Password123!</code>
          </p>
        )}
      </form>
    </div>
  );
}
