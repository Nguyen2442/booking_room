import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { KeyRound, Pencil, Plus, Search, Users as UsersIcon, Wand2 } from 'lucide-react';
import { api, ApiError } from '../api/client';
import type { Page, Role, User } from '../api/types';
import { Badge, Banner, Button, EmptyState, ErrorState, Field, Modal, PageHeader, Pagination, Skeleton, useDebounced } from '../components/ui';
import { useAuth } from '../lib/auth';
import { useI18n } from '../lib/i18n';
import { useToast } from '../lib/toast';

function generatePassword() {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%';
  const buf = new Uint32Array(14);
  crypto.getRandomValues(buf);
  return Array.from(buf, (n) => chars[n % chars.length]).join('');
}

export function AdminUsersPage() {
  const { user: me } = useAuth();
  const { t } = useI18n();
  const [search, setSearch] = useState('');
  const [role, setRole] = useState<'' | Role>('');
  const [active, setActive] = useState<'' | 'true' | 'false'>('');
  const [page, setPage] = useState(0);
  const q = useDebounced(search.trim());
  const [dialog, setDialog] = useState<{ kind: 'create' } | { kind: 'edit'; user: User } | { kind: 'password'; user: User } | null>(null);
  const qc = useQueryClient();

  const users = useQuery({
    queryKey: ['users', q, role, active, page],
    queryFn: () => api<Page<User>>('/admin/users', { query: { q, role, active, page, size: 15 } }),
    placeholderData: (prev) => prev,
  });
  const refresh = () => qc.invalidateQueries({ queryKey: ['users'] }).then(() => qc.invalidateQueries({ queryKey: ['bk'] }));

  return (
    <>
      <PageHeader title={t('au.title')} subtitle={t('au.subtitle')} actions={<Button variant="primary" icon={<Plus size={18} />} onClick={() => setDialog({ kind: 'create' })}>{t('au.add')}</Button>} />
      <div className="card filters">
        <Field label={t('au.search')}><div className="input-icon"><Search size={16} aria-hidden /><input value={search} onChange={(e) => { setSearch(e.target.value); setPage(0); }} placeholder={t('au.searchPh')} /></div></Field>
        <Field label={t('au.role')}><select value={role} onChange={(e) => { setRole(e.target.value as Role | ''); setPage(0); }}><option value="">{t('au.allRoles')}</option><option value="USER">{t('role.user')}</option><option value="ADMIN">{t('role.admin')}</option></select></Field>
        <Field label={t('common.status')}><select value={active} onChange={(e) => { setActive(e.target.value as '' | 'true' | 'false'); setPage(0); }}><option value="">{t('common.all')}</option><option value="true">{t('au.active')}</option><option value="false">{t('au.disabled')}</option></select></Field>
      </div>

      {users.isPending && <div className="card stack">{Array.from({ length: 6 }, (_, i) => <Skeleton key={i} h={36} />)}</div>}
      {users.isError && <ErrorState error={users.error} onRetry={() => users.refetch()} />}
      {users.data && users.data.content.length === 0 && <EmptyState icon={<UsersIcon size={28} />} title={t('au.emptyTitle')}>{t('au.emptyHelp')}</EmptyState>}
      {users.data && users.data.content.length > 0 && (
        <div className="card table-wrap">
          <table className="table">
            <thead><tr><th>{t('au.colName')}</th><th>{t('au.role')}</th><th>{t('common.status')}</th><th><span className="sr-only">{t('common.actions')}</span></th></tr></thead>
            <tbody>
              {users.data.content.map((u) => (
                <tr key={u.id}>
                  <td><strong>{u.fullName}</strong>{u.id === me?.id && <> <Badge tone="accent">{t('common.you')}</Badge></>}<div className="muted small">{u.email}</div></td>
                  <td>{u.role === 'ADMIN' ? <Badge tone="accent">{t('role.admin')}</Badge> : t('role.user')}</td>
                  <td>{u.active ? <Badge tone="success">{t('au.active')}</Badge> : <Badge tone="danger">{t('au.disabled')}</Badge>}</td>
                  <td className="actions">
                    <Button variant="ghost" icon={<Pencil size={16} />} onClick={() => setDialog({ kind: 'edit', user: u })} aria-label={t('au.edit', { name: u.fullName })}>{t('common.edit')}</Button>
                    <Button variant="ghost" icon={<KeyRound size={16} />} onClick={() => setDialog({ kind: 'password', user: u })} aria-label={t('au.resetFor', { name: u.fullName })}>{t('au.passwordBtn')}</Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <Pagination page={users.data?.page ?? 0} totalPages={users.data?.totalPages ?? 0} onChange={setPage} />

      <Modal open={dialog?.kind === 'create'} onClose={() => setDialog(null)} title={t('au.createTitle')}>
        {dialog?.kind === 'create' && <CreateUserForm onClose={() => setDialog(null)} onSaved={refresh} />}
      </Modal>
      <Modal open={dialog?.kind === 'edit'} onClose={() => setDialog(null)} title={t('au.editTitle')}>
        {dialog?.kind === 'edit' && <EditUserForm user={dialog.user} self={dialog.user.id === me?.id} onClose={() => setDialog(null)} onSaved={refresh} />}
      </Modal>
      <Modal open={dialog?.kind === 'password'} onClose={() => setDialog(null)} title={t('au.resetTitle')}>
        {dialog?.kind === 'password' && <ResetPasswordForm user={dialog.user} onClose={() => setDialog(null)} />}
      </Modal>
    </>
  );
}

function CreateUserForm({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const { t, err } = useI18n();
  const toast = useToast();
  const [f, setF] = useState({ email: '', fullName: '', password: '', role: 'USER' as Role });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [serverError, setServerError] = useState('');
  const [reveal, setReveal] = useState(false);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setF({ ...f, [k]: e.target.value });

  const create = useMutation({
    mutationFn: () => api<User>('/admin/users', { method: 'POST', body: { ...f, email: f.email.trim(), fullName: f.fullName.trim() } }),
    onSuccess: () => { toast.success(t('au.created')); onSaved(); onClose(); },
    onError: (e) => {
      if (e instanceof ApiError && e.code === 'EMAIL_ALREADY_EXISTS') setErrors({ email: t('au.emailTaken') });
      else if (e instanceof ApiError && e.code === 'PASSWORD_TOO_LONG') setErrors({ password: err(e) });
      else if (e instanceof ApiError && Object.keys(e.fieldErrors).length) setErrors(e.fieldErrors);
      else setServerError(err(e));
    },
  });

  function submit(e: React.FormEvent) {
    e.preventDefault();
    const n: Record<string, string> = {};
    if (!/^\S+@\S+\.\S+$/.test(f.email.trim())) n.email = t('au.invalidEmail');
    if (!f.fullName.trim()) n.fullName = t('au.nameRequired');
    if (f.password.length < 8) n.password = t('au.pwMin');
    setErrors(n); setServerError('');
    if (!Object.keys(n).length) create.mutate();
  }

  return (
    <form className="form" onSubmit={submit} noValidate>
      <Field label={t('au.fullName')} error={errors.fullName}><input value={f.fullName} onChange={set('fullName')} autoFocus /></Field>
      <Field label={t('au.email')} error={errors.email}><input type="email" value={f.email} onChange={set('email')} /></Field>
      <Field label={t('au.initialPw')} error={errors.password} hint={t('au.initialPwHint')}>
        <div className="input-group">
          <input type={reveal ? 'text' : 'password'} value={f.password} onChange={set('password')} autoComplete="new-password" />
          <button type="button" className="icon-btn" aria-label={t('au.generate')} title={t('au.generate')} onClick={() => { setF({ ...f, password: generatePassword() }); setReveal(true); }}><Wand2 size={18} /></button>
        </div>
      </Field>
      <Field label={t('au.role')}><select value={f.role} onChange={set('role')}><option value="USER">{t('role.user')}</option><option value="ADMIN">{t('role.admin')}</option></select></Field>
      {serverError && <Banner tone="danger">{serverError}</Banner>}
      <div className="form-actions"><Button variant="ghost" onClick={onClose}>{t('common.dismiss')}</Button><Button variant="primary" type="submit" loading={create.isPending}>{t('au.create')}</Button></div>
    </form>
  );
}

function EditUserForm({ user, self, onClose, onSaved }: { user: User; self: boolean; onClose: () => void; onSaved: () => void }) {
  const { t, err } = useI18n();
  const toast = useToast();
  const [fullName, setFullName] = useState(user.fullName);
  const [role, setRole] = useState<Role>(user.role);
  const [active, setActive] = useState(user.active);
  const [error, setError] = useState('');
  const save = useMutation({
    mutationFn: () => api<User>(`/admin/users/${user.id}`, { method: 'PUT', body: { fullName: fullName.trim(), role, active } }),
    onSuccess: () => { toast.success(t('au.updated')); onSaved(); onClose(); },
    onError: (e) => setError(err(e)),
  });
  const deactivating = user.active && !active;
  return (
    <form className="form" onSubmit={(e) => { e.preventDefault(); if (!fullName.trim()) return setError(t('au.nameRequired')); setError(''); save.mutate(); }} noValidate>
      <p className="muted small">{user.email}</p>
      <Field label={t('au.fullName')}><input value={fullName} onChange={(e) => setFullName(e.target.value)} autoFocus /></Field>
      <Field label={t('au.role')} hint={self ? t('au.cantRole') : undefined}>
        <select value={role} onChange={(e) => setRole(e.target.value as Role)} disabled={self}><option value="USER">{t('role.user')}</option><option value="ADMIN">{t('role.admin')}</option></select>
      </Field>
      <label className="check"><input type="checkbox" checked={active} disabled={self} onChange={(e) => setActive(e.target.checked)} /> {t('au.activeLabel')}{self && <span className="muted small"> {t('au.cantDisableSelf')}</span>}</label>
      {deactivating && <Banner tone="warning">{t('au.disableWarn')}</Banner>}
      {error && <Banner tone="danger">{error}</Banner>}
      <div className="form-actions"><Button variant="ghost" onClick={onClose}>{t('common.dismiss')}</Button><Button variant={deactivating ? 'danger' : 'primary'} type="submit" loading={save.isPending}>{deactivating ? t('au.disableBtn') : t('common.save')}</Button></div>
    </form>
  );
}

function ResetPasswordForm({ user, onClose }: { user: User; onClose: () => void }) {
  const { t, err } = useI18n();
  const toast = useToast();
  const [pw, setPw] = useState('');
  const [error, setError] = useState('');
  const reset = useMutation({
    mutationFn: () => api(`/admin/users/${user.id}/reset-password`, { method: 'POST', body: { newPassword: pw } }),
    onSuccess: () => { toast.success(t('au.pwChanged', { name: user.fullName })); onClose(); },
    onError: (e) => setError(err(e)),
  });
  return (
    <form className="form" onSubmit={(e) => { e.preventDefault(); if (pw.length < 8) return setError(t('au.pwMin')); setError(''); reset.mutate(); }} noValidate>
      <p className="muted">{t('au.resetBody', { name: user.fullName })}</p>
      <Field label={t('au.newPassword')} error={error}>
        <div className="input-group">
          <input type="text" value={pw} onChange={(e) => setPw(e.target.value)} autoComplete="off" autoFocus />
          <button type="button" className="icon-btn" aria-label={t('au.generate')} title={t('au.generate')} onClick={() => setPw(generatePassword())}><Wand2 size={18} /></button>
        </div>
      </Field>
      <div className="form-actions"><Button variant="ghost" onClick={onClose}>{t('common.dismiss')}</Button><Button variant="primary" type="submit" loading={reset.isPending}>{t('au.setPassword')}</Button></div>
    </form>
  );
}
