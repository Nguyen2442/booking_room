import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { DoorOpen, Pencil, Plus, Power, X } from 'lucide-react';
import { api, ApiError } from '../api/client';
import { useRooms } from '../api/hooks';
import type { Room } from '../api/types';
import { Badge, Banner, Button, Chip, ConfirmDialog, EmptyState, ErrorState, Field, Modal, PageHeader, Skeleton } from '../components/ui';
import { useI18n } from '../lib/i18n';
import type { Key } from '../lib/messages';
import { useToast } from '../lib/toast';

const SUGGESTED = ['PROJECTOR', 'TV', 'WHITEBOARD', 'VIDEO_CONFERENCE', 'MICROPHONE'];
type Filter = 'all' | 'active' | 'inactive';
const FILTER_LABEL: Record<Filter, Key> = { all: 'common.all', active: 'ar.active', inactive: 'ar.inactive' };

export function AdminRoomsPage() {
  const { t, eq, err } = useI18n();
  const [filter, setFilter] = useState<Filter>('all');
  const rooms = useRooms(filter === 'all' ? undefined : filter === 'active');
  const [editing, setEditing] = useState<Room | 'new' | null>(null);
  const [toDeactivate, setToDeactivate] = useState<Room | null>(null);
  const qc = useQueryClient();
  const toast = useToast();

  const refresh = () => qc.invalidateQueries({ queryKey: ['rooms'] }).then(() => qc.invalidateQueries({ queryKey: ['bk'] }));

  const deactivate = useMutation({
    mutationFn: (r: Room) => api(`/rooms/${r.id}`, { method: 'DELETE' }),
    onSuccess: () => { toast.success(t('ar.deactivated')); setToDeactivate(null); refresh(); },
    onError: (e) => { toast.error(err(e)); setToDeactivate(null); },
  });
  const activate = useMutation({
    mutationFn: (r: Room) => api(`/rooms/${r.id}/activate`, { method: 'POST' }),
    onSuccess: () => { toast.success(t('ar.activated')); refresh(); },
    onError: (e) => toast.error(err(e)),
  });

  return (
    <>
      <PageHeader
        title={t('ar.title')}
        subtitle={t('ar.subtitle')}
        actions={<Button variant="primary" icon={<Plus size={18} />} onClick={() => setEditing('new')}>{t('ar.add')}</Button>}
      />
      <div className="tabs" role="tablist">
        {(['all', 'active', 'inactive'] as Filter[]).map((f) => (
          <button key={f} role="tab" aria-selected={filter === f} className={`tab ${filter === f ? 'active' : ''}`} onClick={() => setFilter(f)}>
            {t(FILTER_LABEL[f])}
          </button>
        ))}
      </div>

      {rooms.isPending && <div className="card stack">{Array.from({ length: 4 }, (_, i) => <Skeleton key={i} h={36} />)}</div>}
      {rooms.isError && <ErrorState error={rooms.error} onRetry={() => rooms.refetch()} />}
      {rooms.data && rooms.data.content.length === 0 && <EmptyState icon={<DoorOpen size={28} />} title={t('ar.emptyTitle')}>{t('ar.emptyHelp')}</EmptyState>}
      {rooms.data && rooms.data.content.length > 0 && (
        <div className="card table-wrap">
          <table className="table">
            <thead><tr><th>{t('common.room')}</th><th>{t('ar.colSeats')}</th><th>{t('ar.colEquipment')}</th><th>{t('common.status')}</th><th><span className="sr-only">{t('common.actions')}</span></th></tr></thead>
            <tbody>
              {rooms.data.content.map((r) => (
                <tr key={r.id}>
                  <td><strong>{r.name}</strong>{r.location && <div className="muted small">{r.location}</div>}</td>
                  <td>{r.capacity}</td>
                  <td><div className="quick">{r.equipment.length ? r.equipment.map((e) => <Chip key={e}>{eq(e)}</Chip>) : <span className="muted">—</span>}</div></td>
                  <td>{r.active ? <Badge tone="success">{t('ar.active')}</Badge> : <Badge>{t('ar.inactive')}</Badge>}</td>
                  <td className="actions">
                    <Button variant="ghost" icon={<Pencil size={16} />} onClick={() => setEditing(r)} aria-label={t('ar.edit', { name: r.name })}>{t('common.edit')}</Button>
                    {r.active
                      ? <Button variant="ghost" icon={<Power size={16} />} onClick={() => setToDeactivate(r)} aria-label={t('ar.deactivateAria', { name: r.name })}>{t('ar.deactivate')}</Button>
                      : <Button variant="ghost" icon={<Power size={16} />} loading={activate.isPending && activate.variables?.id === r.id} onClick={() => activate.mutate(r)} aria-label={t('ar.activateAria', { name: r.name })}>{t('ar.activate')}</Button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <RoomFormDialog room={editing} onClose={() => setEditing(null)} onSaved={refresh} />
      <ConfirmDialog
        open={!!toDeactivate}
        danger
        title={t('ar.confirmTitle', { name: toDeactivate?.name ?? '' })}
        message={t('ar.confirmBody')}
        confirmLabel={t('ar.deactivate')}
        loading={deactivate.isPending}
        onConfirm={() => toDeactivate && deactivate.mutate(toDeactivate)}
        onCancel={() => setToDeactivate(null)}
      />
    </>
  );
}

function RoomFormDialog({ room, onClose, onSaved }: { room: Room | 'new' | null; onClose: () => void; onSaved: () => void }) {
  const { t } = useI18n();
  return (
    <Modal open={!!room} onClose={onClose} title={room === 'new' ? t('ar.formAdd') : t('ar.formEdit')}>
      {room && <RoomForm key={room === 'new' ? 'new' : room.id} room={room === 'new' ? undefined : room} onClose={onClose} onSaved={onSaved} />}
    </Modal>
  );
}

function RoomForm({ room, onClose, onSaved }: { room?: Room; onClose: () => void; onSaved: () => void }) {
  const { t, eq, err } = useI18n();
  const toast = useToast();
  const [name, setName] = useState(room?.name ?? '');
  const [location, setLocation] = useState(room?.location ?? '');
  const [capacity, setCapacity] = useState(String(room?.capacity ?? ''));
  const [description, setDescription] = useState(room?.description ?? '');
  const [equipment, setEquipment] = useState<string[]>(room?.equipment ?? []);
  const [custom, setCustom] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [serverError, setServerError] = useState('');

  const save = useMutation({
    mutationFn: () => {
      const body = { name: name.trim(), location: location.trim() || undefined, capacity: Number(capacity), equipment, description: description.trim() || undefined };
      return room ? api<Room>(`/rooms/${room.id}`, { method: 'PUT', body }) : api<Room>('/rooms', { method: 'POST', body });
    },
    onSuccess: () => { toast.success(room ? t('ar.updated') : t('ar.created')); onSaved(); onClose(); },
    onError: (e) => {
      if (e instanceof ApiError && e.code === 'ROOM_NAME_EXISTS') setErrors({ name: t('ar.nameTaken') });
      else if (e instanceof ApiError && Object.keys(e.fieldErrors).length) setErrors(e.fieldErrors);
      else setServerError(err(e));
    },
  });

  function addTag(raw: string) {
    const tag = raw.trim().toUpperCase().replace(/\s+/g, '_');
    if (tag && !equipment.includes(tag) && equipment.length < 30) setEquipment([...equipment, tag]);
    setCustom('');
  }

  function submit(e: React.FormEvent) {
    e.preventDefault();
    const next: Record<string, string> = {};
    if (!name.trim()) next.name = t('ar.nameRequired');
    const cap = Number(capacity);
    if (!capacity || !Number.isInteger(cap) || cap < 1 || cap > 1000) next.capacity = t('ar.seatsInvalid');
    setErrors(next);
    setServerError('');
    if (Object.keys(next).length === 0) save.mutate();
  }

  return (
    <form className="form" onSubmit={submit} noValidate>
      <div className="grid-2">
        <Field label={t('ar.name')} error={errors.name}><input value={name} maxLength={100} autoFocus onChange={(e) => setName(e.target.value)} /></Field>
        <Field label={t('ar.seats')} error={errors.capacity}><input type="number" inputMode="numeric" min={1} max={1000} value={capacity} onChange={(e) => setCapacity(e.target.value)} /></Field>
      </div>
      <Field label={t('ar.location')} error={errors.location}><input value={location} maxLength={200} onChange={(e) => setLocation(e.target.value)} placeholder={t('ar.locationPh')} /></Field>
      <div className="field">
        <span className="field-label">{t('ar.colEquipment')}</span>
        <div className="quick">
          {SUGGESTED.map((s) => (
            <button key={s} type="button" aria-pressed={equipment.includes(s)} className={`chip chip-btn ${equipment.includes(s) ? 'chip-active' : ''}`}
              onClick={() => setEquipment(equipment.includes(s) ? equipment.filter((x) => x !== s) : [...equipment, s])}>
              {eq(s)}
            </button>
          ))}
          {equipment.filter((e) => !SUGGESTED.includes(e)).map((e) => (
            <span key={e} className="chip chip-active">{eq(e)}
              <button type="button" className="chip-x" aria-label={t('ar.remove', { name: eq(e) })} onClick={() => setEquipment(equipment.filter((x) => x !== e))}><X size={12} /></button>
            </span>
          ))}
        </div>
        <input value={custom} placeholder={t('ar.otherEquip')} onChange={(e) => setCustom(e.target.value)} maxLength={50}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addTag(custom); } }} onBlur={() => custom && addTag(custom)} />
      </div>
      <Field label={t('ar.desc')} error={errors.description}><textarea rows={2} maxLength={1000} value={description} onChange={(e) => setDescription(e.target.value)} /></Field>
      {serverError && <Banner tone="danger">{serverError}</Banner>}
      <div className="form-actions">
        <Button variant="ghost" onClick={onClose} disabled={save.isPending}>{t('common.dismiss')}</Button>
        <Button variant="primary" type="submit" loading={save.isPending}>{room ? t('common.save') : t('ar.create')}</Button>
      </div>
    </form>
  );
}
