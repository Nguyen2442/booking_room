import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ListChecks, Search, Wrench, X } from 'lucide-react';
import { api } from '../api/client';
import { useConfig, useRooms } from '../api/hooks';
import type { Booking, BookingKind, BookingStatus, Page, User } from '../api/types';
import { BookingDetailDialog } from '../components/BookingDetailDialog';
import { Badge, Button, EmptyState, ErrorState, Field, PageHeader, Pagination, Skeleton, useDebounced } from '../components/ui';
import { useI18n } from '../lib/i18n';
import { addDays, formatDate, toMinutes, wallDate, wallTime, zonedToIso } from '../lib/time';

export function AdminBookingsPage() {
  const cfg = useConfig().data!;
  const { t, tn, dur } = useI18n();
  const rooms = useRooms();
  const [roomId, setRoomId] = useState('');
  const [status, setStatus] = useState<'' | BookingStatus>('');
  const [kind, setKind] = useState<'' | BookingKind>('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [person, setPerson] = useState<User | null>(null);
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<Booking | null>(null);

  const q = useQuery({
    queryKey: ['bk', 'all', roomId, status, kind, from, to, person?.id, page],
    queryFn: () =>
      api<Page<Booking>>('/bookings', {
        query: {
          roomId, status, kind, userId: person?.id, page, size: 15, sort: 'startTime,desc',
          from: from ? zonedToIso(from, '00:00', cfg.timezone) : undefined,
          to: to ? zonedToIso(addDays(to, 1), '00:00', cfg.timezone) : undefined, // inclusive end date
        },
      }),
    placeholderData: (prev) => prev,
  });
  const reset = () => { setRoomId(''); setStatus(''); setKind(''); setFrom(''); setTo(''); setPerson(null); setPage(0); };
  const filtered = roomId || status || kind || from || to || person;

  return (
    <>
      <PageHeader title={t('ab.title')} subtitle={t('ab.subtitle')} />
      <div className="card filters">
        <Field label={t('common.room')}><select value={roomId} onChange={(e) => { setRoomId(e.target.value); setPage(0); }}><option value="">{t('ab.allRooms')}</option>{rooms.data?.content.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}</select></Field>
        <Field label={t('common.status')}><select value={status} onChange={(e) => { setStatus(e.target.value as '' | BookingStatus); setPage(0); }}><option value="">{t('ab.any')}</option><option value="CONFIRMED">{t('ab.confirmed')}</option><option value="CANCELLED">{t('dt.cancelled')}</option></select></Field>
        <Field label={t('ab.type')}><select value={kind} onChange={(e) => { setKind(e.target.value as '' | BookingKind); setPage(0); }}><option value="">{t('ab.any')}</option><option value="BOOKING">{t('ab.typeBooking')}</option><option value="BLOCK">{t('ab.typeBlock')}</option></select></Field>
        <Field label={t('ab.from')}><input type="date" value={from} max={to || undefined} onChange={(e) => { setFrom(e.target.value); setPage(0); }} /></Field>
        <Field label={t('ab.to')}><input type="date" value={to} min={from || undefined} onChange={(e) => { setTo(e.target.value); setPage(0); }} /></Field>
        <UserPicker value={person} onChange={(u) => { setPerson(u); setPage(0); }} />
        {filtered && <div className="filters-wide"><Button variant="ghost" icon={<X size={16} />} onClick={reset}>{t('ab.clear')}</Button></div>}
      </div>

      {q.isPending && <div className="card stack">{Array.from({ length: 6 }, (_, i) => <Skeleton key={i} h={36} />)}</div>}
      {q.isError && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
      {q.data && q.data.content.length === 0 && <EmptyState icon={<ListChecks size={28} />} title={t('ab.emptyTitle')}>{filtered ? t('ab.emptyFiltered') : t('ab.empty')}</EmptyState>}
      {q.data && q.data.content.length > 0 && (
        <div className="card table-wrap">
          <table className="table">
            <thead><tr><th>{t('ab.colWhen')}</th><th>{t('common.room')}</th><th>{t('ab.colMeeting')}</th><th>{t('ab.bookedBy')}</th><th>{t('common.status')}</th><th><span className="sr-only">{t('common.actions')}</span></th></tr></thead>
            <tbody>
              {q.data.content.map((b) => (
                <tr key={b.id}>
                  <td><strong>{formatDate(wallDate(b.startTime), { weekday: 'short', day: 'numeric', month: 'short' })}</strong><div className="muted small">{wallTime(b.startTime)}–{wallTime(b.endTime)} ({dur(toMinutes(wallTime(b.endTime)) - toMinutes(wallTime(b.startTime)))})</div></td>
                  <td>{b.room.name}</td>
                  <td>{b.kind === 'BLOCK' && <Wrench size={14} aria-hidden className="inline-icon" />}{b.title}</td>
                  <td>{b.booker.fullName}</td>
                  <td>{b.status === 'CANCELLED' ? <Badge tone="danger">{t('dt.cancelled')}</Badge> : b.kind === 'BLOCK' ? <Badge tone="warning">{t('ab.blockBadge')}</Badge> : <Badge tone="success">{t('ab.confirmed')}</Badge>}</td>
                  <td className="actions"><Button variant="ghost" onClick={() => setSelected(b)}>{t('ab.open')}</Button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="muted small">{q.data ? tn('ab.count', q.data.totalElements) : ''}</p>
      <Pagination page={q.data?.page ?? 0} totalPages={q.data?.totalPages ?? 0} onChange={setPage} />
      <BookingDetailDialog booking={selected} onClose={() => setSelected(null)} />
    </>
  );
}

/** Type-ahead user lookup: 200+ employees do not fit in a plain <select>. */
function UserPicker({ value, onChange }: { value: User | null; onChange: (u: User | null) => void }) {
  const { t } = useI18n();
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
      <Field label={t('ab.bookedBy')}>
        <div className="picked"><span>{value.fullName}</span><button type="button" className="icon-btn" aria-label={t('ab.clearPerson')} onClick={() => onChange(null)}><X size={16} /></button></div>
      </Field>
    );
  }
  return (
    <Field label={t('ab.bookedBy')}>
      <div className="picker">
        <div className="input-icon"><Search size={16} aria-hidden /><input value={text} placeholder={t('ab.anyone')} onChange={(e) => { setText(e.target.value); setOpen(true); }} onFocus={() => setOpen(true)} onBlur={() => setTimeout(() => setOpen(false), 150)} /></div>
        {open && res.data && (
          <ul className="picker-list" role="listbox">
            {res.data.content.length === 0 && <li className="muted small pad">{t('ab.noMatches')}</li>}
            {res.data.content.map((u) => (
              <li key={u.id}><button type="button" role="option" aria-selected={false} onMouseDown={(e) => e.preventDefault()} onClick={() => { onChange(u); setText(''); setOpen(false); }}>{u.fullName}<span className="muted small"> {u.email}</span></button></li>
            ))}
          </ul>
        )}
      </div>
    </Field>
  );
}
