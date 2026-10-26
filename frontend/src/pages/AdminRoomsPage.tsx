import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { DoorOpen, Pencil, Plus, Power, X } from 'lucide-react';
import { api, ApiError, errorMessage } from '../api/client';
import { useRooms } from '../api/hooks';
import type { Room } from '../api/types';
import { Badge, Banner, Button, Chip, ConfirmDialog, EmptyState, ErrorState, Field, Modal, PageHeader, Skeleton } from '../components/ui';
import { prettyEquipment } from '../lib/format';
import { useToast } from '../lib/toast';

const SUGGESTED = ['PROJECTOR', 'TV', 'WHITEBOARD', 'VIDEO_CONFERENCE', 'MICROPHONE'];
type Filter = 'all' | 'active' | 'inactive';

export function AdminRoomsPage() {
  const [filter, setFilter] = useState<Filter>('all');
  const rooms = useRooms(filter === 'all' ? undefined : filter === 'active');
  const [editing, setEditing] = useState<Room | 'new' | null>(null);
  const [toDeactivate, setToDeactivate] = useState<Room | null>(null);
  const qc = useQueryClient();
  const toast = useToast();

  const refresh = () => qc.invalidateQueries({ queryKey: ['rooms'] }).then(() => qc.invalidateQueries({ queryKey: ['bk'] }));

  const deactivate = useMutation({
    mutationFn: (r: Room) => api(`/rooms/${r.id}`, { method: 'DELETE' }),
    onSuccess: () => { toast.success('Room deactivated'); setToDeactivate(null); refresh(); },
    onError: (e) => { toast.error(errorMessage(e)); setToDeactivate(null); },
  });
  const activate = useMutation({
    mutationFn: (r: Room) => api(`/rooms/${r.id}/activate`, { method: 'POST' }),
    onSuccess: () => { toast.success('Room is bookable again'); refresh(); },
    onError: (e) => toast.error(errorMessage(e)),
  });

  return (
    <>
      <PageHeader
        title="Rooms"
        subtitle="Create and maintain meeting rooms."
        actions={<Button variant="primary" icon={<Plus size={18} />} onClick={() => setEditing('new')}>Add room</Button>}
      />
      <div className="tabs" role="tablist">
        {(['all', 'active', 'inactive'] as Filter[]).map((f) => (
          <button key={f} role="tab" aria-selected={filter === f} className={`tab ${filter === f ? 'active' : ''}`} onClick={() => setFilter(f)}>
            {f[0].toUpperCase() + f.slice(1)}
          </button>
        ))}
      </div>

      {rooms.isPending && <div className="card stack">{Array.from({ length: 4 }, (_, i) => <Skeleton key={i} h={36} />)}</div>}
      {rooms.isError && <ErrorState error={rooms.error} onRetry={() => rooms.refetch()} />}
      {rooms.data && rooms.data.content.length === 0 && <EmptyState icon={<DoorOpen size={28} />} title="No rooms here">Add your first room to start taking bookings.</EmptyState>}
      {rooms.data && rooms.data.content.length > 0 && (
        <div className="card table-wrap">
          <table className="table">
            <thead><tr><th>Room</th><th>Seats</th><th>Equipment</th><th>Status</th><th><span className="sr-only">Actions</span></th></tr></thead>
            <tbody>
              {rooms.data.content.map((r) => (
                <tr key={r.id}>
                  <td><strong>{r.name}</strong>{r.location && <div className="muted small">{r.location}</div>}</td>
                  <td>{r.capacity}</td>
                  <td><div className="quick">{r.equipment.length ? r.equipment.map((e) => <Chip key={e}>{prettyEquipment(e)}</Chip>) : <span className="muted">—</span>}</div></td>
                  <td>{r.active ? <Badge tone="success">Active</Badge> : <Badge>Inactive</Badge>}</td>
                  <td className="actions">
                    <Button variant="ghost" icon={<Pencil size={16} />} onClick={() => setEditing(r)} aria-label={`Edit ${r.name}`}>Edit</Button>
                    {r.active
                      ? <Button variant="ghost" icon={<Power size={16} />} onClick={() => setToDeactivate(r)} aria-label={`Deactivate ${r.name}`}>Deactivate</Button>
                      : <Button variant="ghost" icon={<Power size={16} />} loading={activate.isPending && activate.variables?.id === r.id} onClick={() => activate.mutate(r)} aria-label={`Activate ${r.name}`}>Activate</Button>}
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
        title={`Deactivate ${toDeactivate?.name}?`}
        message="People will no longer be able to book this room. This is only allowed when it has no upcoming bookings."
        confirmLabel="Deactivate"
        loading={deactivate.isPending}
        onConfirm={() => toDeactivate && deactivate.mutate(toDeactivate)}
        onCancel={() => setToDeactivate(null)}
      />
    </>
  );
}

function RoomFormDialog({ room, onClose, onSaved }: { room: Room | 'new' | null; onClose: () => void; onSaved: () => void }) {
  return (
    <Modal open={!!room} onClose={onClose} title={room === 'new' ? 'Add room' : 'Edit room'}>
      {room && <RoomForm key={room === 'new' ? 'new' : room.id} room={room === 'new' ? undefined : room} onClose={onClose} onSaved={onSaved} />}
    </Modal>
  );
}

function RoomForm({ room, onClose, onSaved }: { room?: Room; onClose: () => void; onSaved: () => void }) {
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
    onSuccess: () => { toast.success(room ? 'Room updated' : 'Room created'); onSaved(); onClose(); },
    onError: (e) => {
      if (e instanceof ApiError && e.code === 'ROOM_NAME_EXISTS') setErrors({ name: 'A room with this name already exists' });
      else if (e instanceof ApiError && Object.keys(e.fieldErrors).length) setErrors(e.fieldErrors);
      else setServerError(errorMessage(e));
    },
  });

  function addTag(raw: string) {
    const t = raw.trim().toUpperCase().replace(/\s+/g, '_');
    if (t && !equipment.includes(t) && equipment.length < 30) setEquipment([...equipment, t]);
    setCustom('');
  }

  function submit(e: React.FormEvent) {
    e.preventDefault();
    const next: Record<string, string> = {};
    if (!name.trim()) next.name = 'Room name is required';
    const cap = Number(capacity);
    if (!capacity || !Number.isInteger(cap) || cap < 1 || cap > 1000) next.capacity = 'Enter a whole number between 1 and 1000';
    setErrors(next);
    setServerError('');
    if (Object.keys(next).length === 0) save.mutate();
  }

  return (
    <form className="form" onSubmit={submit} noValidate>
      <div className="grid-2">
        <Field label="Name" error={errors.name}><input value={name} maxLength={100} autoFocus onChange={(e) => setName(e.target.value)} /></Field>
        <Field label="Seats" error={errors.capacity}><input type="number" inputMode="numeric" min={1} max={1000} value={capacity} onChange={(e) => setCapacity(e.target.value)} /></Field>
      </div>
      <Field label="Location (optional)" error={errors.location}><input value={location} maxLength={200} onChange={(e) => setLocation(e.target.value)} placeholder="e.g. Floor 3, east wing" /></Field>
      <div className="field">
        <span className="field-label">Equipment</span>
        <div className="quick">
          {SUGGESTED.map((s) => (
            <button key={s} type="button" aria-pressed={equipment.includes(s)} className={`chip chip-btn ${equipment.includes(s) ? 'chip-active' : ''}`}
              onClick={() => setEquipment(equipment.includes(s) ? equipment.filter((x) => x !== s) : [...equipment, s])}>
              {prettyEquipment(s)}
            </button>
          ))}
          {equipment.filter((e) => !SUGGESTED.includes(e)).map((e) => (
            <span key={e} className="chip chip-active">{prettyEquipment(e)}
              <button type="button" className="chip-x" aria-label={`Remove ${e}`} onClick={() => setEquipment(equipment.filter((x) => x !== e))}><X size={12} /></button>
            </span>
          ))}
        </div>
        <input value={custom} placeholder="Add other equipment and press Enter" onChange={(e) => setCustom(e.target.value)} maxLength={50}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addTag(custom); } }} onBlur={() => custom && addTag(custom)} />
      </div>
      <Field label="Description (optional)" error={errors.description}><textarea rows={2} maxLength={1000} value={description} onChange={(e) => setDescription(e.target.value)} /></Field>
      {serverError && <Banner tone="danger">{serverError}</Banner>}
      <div className="form-actions">
        <Button variant="ghost" onClick={onClose} disabled={save.isPending}>Cancel</Button>
        <Button variant="primary" type="submit" loading={save.isPending}>{room ? 'Save changes' : 'Create room'}</Button>
      </div>
    </form>
  );
}
