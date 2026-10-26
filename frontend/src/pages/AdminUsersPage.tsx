import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { KeyRound, Pencil, Plus, Search, Users as UsersIcon, Wand2 } from 'lucide-react';
import { api, ApiError, errorMessage } from '../api/client';
import type { Page, Role, User } from '../api/types';
import { Badge, Banner, Button, EmptyState, ErrorState, Field, Modal, PageHeader, Pagination, Skeleton, useDebounced } from '../components/ui';
import { useAuth } from '../lib/auth';
import { useToast } from '../lib/toast';

function generatePassword() {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%';
  const buf = new Uint32Array(14);
  crypto.getRandomValues(buf);
  return Array.from(buf, (n) => chars[n % chars.length]).join('');
}

export function AdminUsersPage() {
  const { user: me } = useAuth();
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
      <PageHeader title="Users" subtitle="Accounts for your colleagues." actions={<Button variant="primary" icon={<Plus size={18} />} onClick={() => setDialog({ kind: 'create' })}>Add user</Button>} />
      <div className="card filters">
        <Field label="Search"><div className="input-icon"><Search size={16} aria-hidden /><input value={search} onChange={(e) => { setSearch(e.target.value); setPage(0); }} placeholder="Name or email" /></div></Field>
        <Field label="Role"><select value={role} onChange={(e) => { setRole(e.target.value as Role | ''); setPage(0); }}><option value="">All roles</option><option value="USER">User</option><option value="ADMIN">Admin</option></select></Field>
        <Field label="Status"><select value={active} onChange={(e) => { setActive(e.target.value as '' | 'true' | 'false'); setPage(0); }}><option value="">All</option><option value="true">Active</option><option value="false">Disabled</option></select></Field>
      </div>

      {users.isPending && <div className="card stack">{Array.from({ length: 6 }, (_, i) => <Skeleton key={i} h={36} />)}</div>}
      {users.isError && <ErrorState error={users.error} onRetry={() => users.refetch()} />}
      {users.data && users.data.content.length === 0 && <EmptyState icon={<UsersIcon size={28} />} title="No users match">Try a different search or filter.</EmptyState>}
      {users.data && users.data.content.length > 0 && (
        <div className="card table-wrap">
          <table className="table">
            <thead><tr><th>Name</th><th>Role</th><th>Status</th><th><span className="sr-only">Actions</span></th></tr></thead>
            <tbody>
              {users.data.content.map((u) => (
                <tr key={u.id}>
                  <td><strong>{u.fullName}</strong>{u.id === me?.id && <> <Badge tone="accent">You</Badge></>}<div className="muted small">{u.email}</div></td>
                  <td>{u.role === 'ADMIN' ? <Badge tone="accent">Admin</Badge> : 'User'}</td>
                  <td>{u.active ? <Badge tone="success">Active</Badge> : <Badge tone="danger">Disabled</Badge>}</td>
                  <td className="actions">
                    <Button variant="ghost" icon={<Pencil size={16} />} onClick={() => setDialog({ kind: 'edit', user: u })} aria-label={`Edit ${u.fullName}`}>Edit</Button>
                    <Button variant="ghost" icon={<KeyRound size={16} />} onClick={() => setDialog({ kind: 'password', user: u })} aria-label={`Reset password for ${u.fullName}`}>Password</Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <Pagination page={users.data?.page ?? 0} totalPages={users.data?.totalPages ?? 0} onChange={setPage} />

      <Modal open={dialog?.kind === 'create'} onClose={() => setDialog(null)} title="Add user">
        {dialog?.kind === 'create' && <CreateUserForm onClose={() => setDialog(null)} onSaved={refresh} />}
      </Modal>
      <Modal open={dialog?.kind === 'edit'} onClose={() => setDialog(null)} title="Edit user">
        {dialog?.kind === 'edit' && <EditUserForm user={dialog.user} self={dialog.user.id === me?.id} onClose={() => setDialog(null)} onSaved={refresh} />}
      </Modal>
      <Modal open={dialog?.kind === 'password'} onClose={() => setDialog(null)} title="Reset password">
        {dialog?.kind === 'password' && <ResetPasswordForm user={dialog.user} onClose={() => setDialog(null)} />}
      </Modal>
    </>
  );
}

function CreateUserForm({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const toast = useToast();
  const [f, setF] = useState({ email: '', fullName: '', password: '', role: 'USER' as Role });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [serverError, setServerError] = useState('');
  const [reveal, setReveal] = useState(false);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setF({ ...f, [k]: e.target.value });

  const create = useMutation({
    mutationFn: () => api<User>('/admin/users', { method: 'POST', body: { ...f, email: f.email.trim(), fullName: f.fullName.trim() } }),
    onSuccess: () => { toast.success('User created'); onSaved(); onClose(); },
    onError: (e) => {
      if (e instanceof ApiError && e.code === 'EMAIL_ALREADY_EXISTS') setErrors({ email: 'This email is already registered' });
      else if (e instanceof ApiError && e.code === 'PASSWORD_TOO_LONG') setErrors({ password: e.message });
      else if (e instanceof ApiError && Object.keys(e.fieldErrors).length) setErrors(e.fieldErrors);
      else setServerError(errorMessage(e));
    },
  });

  function submit(e: React.FormEvent) {
    e.preventDefault();
    const n: Record<string, string> = {};
    if (!/^\S+@\S+\.\S+$/.test(f.email.trim())) n.email = 'Enter a valid email address';
    if (!f.fullName.trim()) n.fullName = 'Name is required';
    if (f.password.length < 8) n.password = 'At least 8 characters';
    setErrors(n); setServerError('');
    if (!Object.keys(n).length) create.mutate();
  }

  return (
    <form className="form" onSubmit={submit} noValidate>
      <Field label="Full name" error={errors.fullName}><input value={f.fullName} onChange={set('fullName')} autoFocus /></Field>
      <Field label="Email" error={errors.email}><input type="email" value={f.email} onChange={set('email')} /></Field>
      <Field label="Initial password" error={errors.password} hint="Share it securely; the user can change it after signing in.">
        <div className="input-group">
          <input type={reveal ? 'text' : 'password'} value={f.password} onChange={set('password')} autoComplete="new-password" />
          <button type="button" className="icon-btn" aria-label="Generate a strong password" title="Generate" onClick={() => { setF({ ...f, password: generatePassword() }); setReveal(true); }}><Wand2 size={18} /></button>
        </div>
      </Field>
      <Field label="Role"><select value={f.role} onChange={set('role')}><option value="USER">User</option><option value="ADMIN">Admin</option></select></Field>
      {serverError && <Banner tone="danger">{serverError}</Banner>}
      <div className="form-actions"><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" type="submit" loading={create.isPending}>Create user</Button></div>
    </form>
  );
}

function EditUserForm({ user, self, onClose, onSaved }: { user: User; self: boolean; onClose: () => void; onSaved: () => void }) {
  const toast = useToast();
  const [fullName, setFullName] = useState(user.fullName);
  const [role, setRole] = useState<Role>(user.role);
  const [active, setActive] = useState(user.active);
  const [error, setError] = useState('');
  const save = useMutation({
    mutationFn: () => api<User>(`/admin/users/${user.id}`, { method: 'PUT', body: { fullName: fullName.trim(), role, active } }),
    onSuccess: () => { toast.success('User updated'); onSaved(); onClose(); },
    onError: (e) => setError(errorMessage(e)),
  });
  const deactivating = user.active && !active;
  return (
    <form className="form" onSubmit={(e) => { e.preventDefault(); if (!fullName.trim()) return setError('Name is required'); setError(''); save.mutate(); }} noValidate>
      <p className="muted small">{user.email}</p>
      <Field label="Full name"><input value={fullName} onChange={(e) => setFullName(e.target.value)} autoFocus /></Field>
      <Field label="Role" hint={self ? 'You cannot change your own role.' : undefined}>
        <select value={role} onChange={(e) => setRole(e.target.value as Role)} disabled={self}><option value="USER">User</option><option value="ADMIN">Admin</option></select>
      </Field>
      <label className="check"><input type="checkbox" checked={active} disabled={self} onChange={(e) => setActive(e.target.checked)} /> Account is active{self && <span className="muted small"> (you cannot disable yourself)</span>}</label>
      {deactivating && <Banner tone="warning">Disabling this account signs them out of new actions and <strong>cancels all their upcoming bookings</strong>.</Banner>}
      {error && <Banner tone="danger">{error}</Banner>}
      <div className="form-actions"><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant={deactivating ? 'danger' : 'primary'} type="submit" loading={save.isPending}>{deactivating ? 'Disable account' : 'Save changes'}</Button></div>
    </form>
  );
}

function ResetPasswordForm({ user, onClose }: { user: User; onClose: () => void }) {
  const toast = useToast();
  const [pw, setPw] = useState('');
  const [error, setError] = useState('');
  const reset = useMutation({
    mutationFn: () => api(`/admin/users/${user.id}/reset-password`, { method: 'POST', body: { newPassword: pw } }),
    onSuccess: () => { toast.success(`Password changed for ${user.fullName}`); onClose(); },
    onError: (e) => setError(errorMessage(e)),
  });
  return (
    <form className="form" onSubmit={(e) => { e.preventDefault(); if (pw.length < 8) return setError('At least 8 characters'); setError(''); reset.mutate(); }} noValidate>
      <p className="muted">Set a new password for <strong>{user.fullName}</strong>.</p>
      <Field label="New password" error={error}>
        <div className="input-group">
          <input type="text" value={pw} onChange={(e) => setPw(e.target.value)} autoComplete="off" autoFocus />
          <button type="button" className="icon-btn" aria-label="Generate a strong password" title="Generate" onClick={() => setPw(generatePassword())}><Wand2 size={18} /></button>
        </div>
      </Field>
      <div className="form-actions"><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" type="submit" loading={reset.isPending}>Set password</Button></div>
    </form>
  );
}
