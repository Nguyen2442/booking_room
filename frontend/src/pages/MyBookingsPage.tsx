import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { CalendarX, Ticket } from 'lucide-react';
import { api } from '../api/client';
import type { Booking, Page } from '../api/types';
import { BookingDetailDialog, bookingPermissions } from '../components/BookingDetailDialog';
import { Badge, Button, EmptyState, ErrorState, PageHeader, Pagination, Skeleton } from '../components/ui';
import { useAuth } from '../lib/auth';
import { durationLabel, formatDate, toMinutes, wallDate, wallTime } from '../lib/time';
import { Link } from 'react-router-dom';

type Tab = 'upcoming' | 'past' | 'cancelled';
const TABS: { id: Tab; label: string }[] = [
  { id: 'upcoming', label: 'Upcoming' },
  { id: 'past', label: 'Past' },
  { id: 'cancelled', label: 'Cancelled' },
];

export function MyBookingsPage() {
  const { user } = useAuth();
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

  return (
    <>
      <PageHeader title="My bookings" actions={<Link className="btn btn-primary" to="/">Book a room</Link>} />
      <div className="tabs" role="tablist">
        {TABS.map((t) => (
          <button key={t.id} role="tab" aria-selected={tab === t.id} className={`tab ${tab === t.id ? 'active' : ''}`} onClick={() => { setTab(t.id); setPage(0); }}>
            {t.label}
          </button>
        ))}
      </div>

      {q.isPending && <div className="stack">{Array.from({ length: 3 }, (_, i) => <div key={i} className="card"><Skeleton h={18} w="40%" /><Skeleton h={14} w="60%" /></div>)}</div>}
      {q.isError && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
      {q.data && q.data.content.length === 0 && (
        <EmptyState icon={tab === 'cancelled' ? <CalendarX size={28} /> : <Ticket size={28} />} title={`No ${tab} bookings`}>
          {tab === 'upcoming' ? 'Book a room from the schedule and it will show up here.' : 'Nothing to show here.'}
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
                  <div className="booking-title">{b.title} {b.status === 'CANCELLED' && <Badge tone="danger">Cancelled</Badge>}</div>
                  <div className="muted small">
                    {formatDate(wallDate(b.startTime), { weekday: 'short', day: 'numeric', month: 'short' })} · {wallTime(b.startTime)}–{wallTime(b.endTime)} ({durationLabel(mins)}) · {b.room.name}
                  </div>
                </div>
                <div className="booking-actions">
                  <Button variant="secondary" onClick={() => setSelected(b)}>{canCancel ? 'Manage' : 'Details'}</Button>
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
