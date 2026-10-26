import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import { Banner, Button, Field, PageHeader } from '../components/ui';
import { useAuth } from '../lib/auth';
import { useI18n } from '../lib/i18n';
import { useToast } from '../lib/toast';

export function AccountPage() {
  const { user } = useAuth();
  const { t, err } = useI18n();
  const toast = useToast();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [serverError, setServerError] = useState('');

  const change = useMutation({
    mutationFn: () => api('/auth/change-password', { method: 'POST', body: { currentPassword: current, newPassword: next } }),
    onSuccess: () => {
      toast.success(t('ac.changed'));
      setCurrent(''); setNext(''); setConfirm('');
    },
    onError: (e) => {
      if (e instanceof ApiError && e.code === 'INVALID_CURRENT_PASSWORD') setErrors({ current: err(e) });
      else if (e instanceof ApiError && e.code === 'PASSWORD_TOO_LONG') setErrors({ next: err(e) });
      else setServerError(err(e));
    },
  });

  function submit(e: React.FormEvent) {
    e.preventDefault();
    const n: Record<string, string> = {};
    if (!current) n.current = t('ac.currentRequired');
    if (next.length < 8) n.next = t('au.pwMin');
    else if (next === current) n.next = t('ac.same');
    if (confirm !== next) n.confirm = t('ac.mismatch');
    setErrors(n);
    setServerError('');
    if (!Object.keys(n).length) change.mutate();
  }

  return (
    <>
      <PageHeader title={t('ac.title')} subtitle={t('ac.subtitle')} />
      <div className="account-grid">
        <section className="card stack">
          <h2 className="h2">{user?.fullName}</h2>
          <dl className="profile">
            <div><dt>{t('ac.email')}</dt><dd>{user?.email}</dd></div>
            <div><dt>{t('ac.role')}</dt><dd>{user?.role === 'ADMIN' ? t('role.admin') : t('role.user')}</dd></div>
          </dl>
        </section>
        <form className="card form" onSubmit={submit} noValidate>
          <h2 className="h2">{t('ac.changePw')}</h2>
          <Field label={t('ac.current')} error={errors.current}><input type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} /></Field>
          <Field label={t('ac.new')} error={errors.next} hint={t('ac.newHint')}><input type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} /></Field>
          <Field label={t('ac.confirm')} error={errors.confirm}><input type="password" autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} /></Field>
          {serverError && <Banner tone="danger">{serverError}</Banner>}
          <div className="form-actions"><Button variant="primary" type="submit" loading={change.isPending}>{t('ac.submit')}</Button></div>
        </form>
      </div>
    </>
  );
}
