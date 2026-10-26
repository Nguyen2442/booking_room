import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { CalendarX, Lock, Repeat, Ticket } from 'lucide-react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { Booking, Page } from '../api/types';
import { BookingDetailDialog, bookingPermissions } from '../components/BookingDetailDialog';
import { Badge, Button, EmptyState, ErrorState, PageHeader, Pagination, Skeleton } from '../components/ui';
import { useAuth } from '../lib/auth';
import { useI18n } from '../lib/i18n';
import type { Key } from '../lib/messages';
import { formatDate, toMinutes, wallDate, wallTime } from '../lib/time';

type Tab = 'upcoming' | 'past' | 'cancelled';
const TABS: { id: Tab; label: Key; empty: Key }[] = [
  { id: 'upcoming', label: 'my.upcoming', empty: 'my.emptyUpcoming' },
  { id: 'past', label: 'my.past', empty: 'my.emptyPast' },
  { id: 'cancelled', label: 'my.cancelled', empty: 'my.emptyCancelled' },
];

export function MyBookingsPage() {
  const { user } = useAuth();
  const { t, dur } = useI18n();
  const [tab, setTab] = useState<Tab>('upcoming');
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<Booking | null>(null);

  const q = useQuery({
    queryKey: ['bk', 'mine', tab, page],
    queryFn: () => {
      const now = new Date().toISOString();
      const base = { page, size: 10 };
      if (tab === 'upcoming') return api<Page<Booking>>('/bookings/my', { query: { ...base, status: 'CONFIRMED', from: now, sort: 'startTime,asc' } });
      if (tab === 'past') return api<Page<Booking>>('/bookings/my', { query: { ...base, status: 'CONFIRMED', endedBefore: now, sort: 'startTime,desc' } });
      return api<Page<Booking>>('/bookings/my', { query: { ...base, status: 'CANCELLED', sort: 'startTime,desc' } });
    },
    placeholderData: (prev) => prev,
  });
  const current = TABS.find((x) => x.id === tab)!;

  return (
    <>
      <PageHeader title={t('my.title')} actions={<Link className="btn btn-primary" to="/">{t('my.book')}</Link>} />
      <div className="tabs" role="tablist">
        {TABS.map((x) => (
          <button key={x.id} role="tab" aria-selected={tab === x.id} className={`tab ${tab === x.id ? 'active' : ''}`} onClick={() => { setTab(x.id); setPage(0); }}>
            {t(x.label)}
          </button>
        ))}
      </div>

      {q.isPending && <div className="stack">{Array.from({ length: 3 }, (_, i) => <div key={i} className="card"><Skeleton h={18} w="40%" /><Skeleton h={14} w="60%" /></div>)}</div>}
      {q.isError && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
      {q.data && q.data.content.length === 0 && (
        <EmptyState icon={tab === 'cancelled' ? <CalendarX size={28} /> : <Ticket size={28} />} title={t(current.empty)}>
          {tab === 'upcoming' ? t('my.emptyHelpUpcoming') : t('my.emptyHelpOther')}
        </EmptyState>
      )}
      {q.data && q.data.content.length > 0 && (
        <ul className="list">
          {q.data.content.map((b) => {
            const mins = toMinutes(wallTime(b.endTime)) - toMinutes(wallTime(b.startTime));
            const { canCancel } = bookingPermissions(b, user);
            return (
              <li key={b.id} className="card booking-row">
                <div className="date-tile" aria-hidden>
                  <span>{formatDate(wallDate(b.startTime), { month: 'short' })}</span>
                  <strong>{Number(wallDate(b.startTime).slice(8, 10))}</strong>
                </div>
                <div className="booking-main">
                  <div className="booking-title">
                    {b.title}
                    {b.private && <Badge><Lock size={12} aria-hidden /> {t('priv.label')}</Badge>}
                    {b.seriesId && <Badge tone="accent"><Repeat size={12} aria-hidden /> {t('rec.badge')}</Badge>}
                    {b.status === 'CANCELLED' && <Badge tone="danger">{t('dt.cancelled')}</Badge>}
                  </div>
                  <div className="muted small">
                    {formatDate(wallDate(b.startTime), { weekday: 'short', day: 'numeric', month: 'short' })} · {wallTime(b.startTime)}–{wallTime(b.endTime)} ({dur(mins)}) · {b.room.name}
                    {b.attendees ? ` · ${t('my.people', { n: b.attendees })}` : ''}
                  </div>
                </div>
                <div className="booking-actions">
                  <Button variant="secondary" onClick={() => setSelected(b)}>{canCancel ? t('my.manage') : t('my.details')}</Button>
                </div>
              </li>
            );
          })}
        </ul>
      )}
      <Pagination page={q.data?.page ?? 0} totalPages={q.data?.totalPages ?? 0} onChange={setPage} />
      <BookingDetailDialog booking={selected} onClose={() => setSelected(null)} />
    </>
  );
}
