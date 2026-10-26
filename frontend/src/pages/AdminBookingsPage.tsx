import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ListChecks, Search, X } from 'lucide-react';
import { api } from '../api/client';
import { useConfig, useRooms } from '../api/hooks';
import type { Booking, BookingStatus, Page, User } from '../api/types';
import { BookingDetailDialog } from '../components/BookingDetailDialog';
import { Badge, Button, EmptyState, ErrorState, Field, PageHeader, Pagination, Skeleton, useDebounced } from '../components/ui';
import { durationLabel, formatDate, toMinutes, wallDate, wallTime, zonedToIso, addDays } from '../lib/time';

export function AdminBookingsPage() {
  const cfg = useConfig().data!;
  const rooms = useRooms();
  const [roomId, setRoomId] = useState('');
  const [status, setStatus] = useState<'' | BookingStatus>('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [person, setPerson] = useState<User | null>(null);
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<Booking | null>(null);

  const q = useQuery({
    queryKey: ['bk', 'all', roomId, status, from, to, person?.id, page],
    queryFn: () =>
      api<Page<Booking>>('/bookings', {
        query: {
          roomId, status, userId: person?.id, page, size: 15, sort: 'startTime,desc',
          from: from ? zonedToIso(from, '00:00', cfg.timezone) : undefined,
          to: to ? zonedToIso(addDays(to, 1), '00:00', cfg.timezone) : undefined, // inclusive end date
        },
      }),
    placeholderData: (prev) => prev,
  });
  const reset = () => { setRoomId(''); setStatus(''); setFrom(''); setTo(''); setPerson(null); setPage(0); };
  const filtered = roomId || status || from || to || person;

  return (
    <>
      <PageHeader title="All bookings" subtitle="Find and manage any booking in the company." />
      <div className="card filters">
        <Field label="Room"><select value={roomId} onChange={(e) => { setRoomId(e.target.value); setPage(0); }}><option value="">All rooms</option>{rooms.data?.content.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}</select></Field>
        <Field label="Status"><select value={status} onChange={(e) => { setStatus(e.target.value as '' | BookingStatus); setPage(0); }}><option value="">Any</option><option value="CONFIRMED">Confirmed</option><option value="CANCELLED">Cancelled</option></select></Field>
        <Field label="From"><input type="date" value={from} max={to || undefined} onChange={(e) => { setFrom(e.target.value); setPage(0); }} /></Field>
        <Field label="To"><input type="date" value={to} min={from || undefined} onChange={(e) => { setTo(e.target.value); setPage(0); }} /></Field>
        <UserPicker value={person} onChange={(u) => { setPerson(u); setPage(0); }} />
        {filtered && <div className="filters-wide"><Button variant="ghost" icon={<X size={16} />} onClick={reset}>Clear filters</Button></div>}
      </div>

      {q.isPending && <div className="card stack">{Array.from({ length: 6 }, (_, i) => <Skeleton key={i} h={36} />)}</div>}
      {q.isError && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
      {q.data && q.data.content.length === 0 && <EmptyState icon={<ListChecks size={28} />} title="No bookings found">{filtered ? 'Try clearing some filters.' : 'Bookings will appear here.'}</EmptyState>}
      {q.data && q.data.content.length > 0 && (
        <div className="card table-wrap">
          <table className="table">
            <thead><tr><th>When</th><th>Room</th><th>Meeting</th><th>Booked by</th><th>Status</th><th><span className="sr-only">Actions</span></th></tr></thead>
            <tbody>
              {q.data.content.map((b) => (
                <tr key={b.id}>
                  <td><strong>{formatDate(wallDate(b.startTime), { weekday: 'short', day: 'numeric', month: 'short' })}</strong><div className="muted small">{wallTime(b.startTime)}–{wallTime(b.endTime)} ({durationLabel(toMinutes(wallTime(b.endTime)) - toMinutes(wallTime(b.startTime)))})</div></td>
                  <td>{b.room.name}</td>
                  <td>{b.title}</td>
                  <td>{b.booker.fullName}</td>
                  <td>{b.status === 'CANCELLED' ? <Badge tone="danger">Cancelled</Badge> : <Badge tone="success">Confirmed</Badge>}</td>
                  <td className="actions"><Button variant="ghost" onClick={() => setSelected(b)}>Open</Button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="muted small">{q.data ? `${q.data.totalElements} booking${q.data.totalElements === 1 ? '' : 's'}` : ''}</p>
      <Pagination page={q.data?.page ?? 0} totalPages={q.data?.totalPages ?? 0} onChange={setPage} />
      <BookingDetailDialog booking={selected} onClose={() => setSelected(null)} />
    </>
  );
}

/** Type-ahead user lookup: 200+ employees do not fit in a plain <select>. */
function UserPicker({ value, onChange }: { value: User | null; onChange: (u: User | null) => void }) {
  const [text, setText] = useState('');
  const [open, setOpen] = useState(false);
  const term = useDebounced(text.trim(), 250);
  const res = useQuery({
    queryKey: ['users', 'picker', term],
    queryFn: () => api<Page<User>>('/admin/users', { query: { q: term, size: 8 } }),
    enabled: open && !value,
  });
  if (value) {
    return (
      <Field label="Booked by">
        <div className="picked"><span>{value.fullName}</span><button type="button" className="icon-btn" aria-label="Clear person" onClick={() => onChange(null)}><X size={16} /></button></div>
      </Field>
    );
  }
  return (
    <Field label="Booked by">
      <div className="picker">
        <div className="input-icon"><Search size={16} aria-hidden /><input value={text} placeholder="Anyone" onChange={(e) => { setText(e.target.value); setOpen(true); }} onFocus={() => setOpen(true)} onBlur={() => setTimeout(() => setOpen(false), 150)} /></div>
        {open && res.data && (
          <ul className="picker-list" role="listbox">
            {res.data.content.length === 0 && <li className="muted small pad">No matches</li>}
            {res.data.content.map((u) => (
              <li key={u.id}><button type="button" role="option" aria-selected={false} onMouseDown={(e) => e.preventDefault()} onClick={() => { onChange(u); setText(''); setOpen(false); }}>{u.fullName}<span className="muted small"> {u.email}</span></button></li>
            ))}
          </ul>
        )}
      </div>
    </Field>
  );
}
