import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { CalendarDays, CalendarPlus, Clock, DoorOpen, Pencil, Repeat, Users, User as UserIcon, Wrench, XCircle } from 'lucide-react';
import { api } from '../api/client';
import { useInvalidateBookings } from '../api/hooks';
import type { Booking } from '../api/types';
import { useAuth } from '../lib/auth';
import { useI18n } from '../lib/i18n';
import { downloadIcs } from '../lib/ics';
import { formatDate, hasEnded, hasStarted, toMinutes, wallDate, wallTime } from '../lib/time';
import { useToast } from '../lib/toast';
import { BookingDialog } from './BookingDialog';
import { Badge, Banner, Button, Field, Modal } from './ui';

/** Shared rule: who may change a booking, and when. Mirrors the server, which stays the authority. */
export function bookingPermissions(b: Booking, user: { id: string; role: string } | null) {
  const admin = user?.role === 'ADMIN';
  // room blocks are administrative: only admins act on them
  const mine = !!user && (admin || (b.booker.id === user.id && b.kind === 'BOOKING'));
  const active = b.status === 'CONFIRMED' && !hasEnded(b.endTime);
  return {
    canCancel: mine && active,
    canEdit: mine && b.kind === 'BOOKING' && b.status === 'CONFIRMED' && !hasStarted(b.startTime),
  };
}

type Scope = 'ONE' | 'FOLLOWING';

export function BookingDetailDialog({ booking, onClose }: { booking: Booking | null; onClose: () => void }) {
  const { user } = useAuth();
  const { t, dur, err } = useI18n();
  const toast = useToast();
  const invalidate = useInvalidateBookings();
  const [editing, setEditing] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [scope, setScope] = useState<Scope>('ONE');
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');

  const isBlock = booking?.kind === 'BLOCK';

  const cancel = useMutation({
    mutationFn: async () => {
      const body = { reason: reason.trim() || undefined };
      if (scope === 'FOLLOWING' && booking!.seriesId) {
        return api<{ cancelled: number }>(`/bookings/${booking!.id}/cancel-series`, { method: 'POST', body });
      }
      await api<Booking>(`/bookings/${booking!.id}/cancel`, { method: 'POST', body });
      return { cancelled: 1 };
    },
    onSuccess: (res) => {
      invalidate();
      toast.success(isBlock ? t('dt.lifted') : res.cancelled > 1 ? t('dt.cancelledMany', { n: res.cancelled }) : t('dt.cancelledOk'));
      setCancelling(false);
      setReason('');
      setScope('ONE');
      onClose();
    },
    onError: (e) => setError(err(e)),
  });

  if (!booking) return null;
  const { canCancel, canEdit } = bookingPermissions(booking, user);
  const mins = toMinutes(wallTime(booking.endTime)) - toMinutes(wallTime(booking.startTime));
  const dateLabel = formatDate(wallDate(booking.startTime));
  const live = booking.status === 'CONFIRMED' && !hasEnded(booking.endTime);

  if (editing) {
    return <BookingDialog open onClose={() => { setEditing(false); onClose(); }} editing={booking} />;
  }

  return (
    <>
      <Modal
        open={!cancelling}
        onClose={onClose}
        title={isBlock ? t('dt.blocked') : t('dt.title')}
        footer={
          (canEdit || canCancel || (live && !isBlock)) && (
            <>
              {live && !isBlock && <Button variant="ghost" icon={<CalendarPlus size={16} />} onClick={() => downloadIcs(booking)}>{t('dt.addCal')}</Button>}
              {canCancel && (
                <Button variant="danger" icon={<XCircle size={16} />} onClick={() => { setError(''); setCancelling(true); }}>
                  {isBlock ? t('dt.liftBtn') : t('dt.cancelBtn')}
                </Button>
              )}
              {canEdit && <Button variant="primary" icon={<Pencil size={16} />} onClick={() => setEditing(true)}>{t('common.edit')}</Button>}
            </>
          )
        }
      >
        <div className="detail">
          <div className="detail-title">
            <h3>{isBlock && <Wrench size={18} aria-hidden className="inline-icon" />}{booking.title}</h3>
            <div className="quick">
              {booking.seriesId && <Badge tone="accent"><Repeat size={12} aria-hidden /> {t('rec.badge')}</Badge>}
              {booking.status === 'CANCELLED' ? <Badge tone="danger">{t('dt.cancelled')}</Badge>
                : hasEnded(booking.endTime) ? <Badge>{t('dt.ended')}</Badge>
                : isBlock ? <Badge tone="warning">{t('dt.blocked')}</Badge> : <Badge tone="success">{t('dt.confirmed')}</Badge>}
            </div>
          </div>
          <dl className="detail-list">
            <div><dt><DoorOpen size={16} aria-hidden /> {t('dt.room')}</dt><dd>{booking.room.name}</dd></div>
            <div><dt><CalendarDays size={16} aria-hidden /> {t('dt.date')}</dt><dd>{dateLabel}</dd></div>
            <div><dt><Clock size={16} aria-hidden /> {t('dt.time')}</dt><dd>{wallTime(booking.startTime)} – {wallTime(booking.endTime)} ({dur(mins)})</dd></div>
            <div><dt><UserIcon size={16} aria-hidden /> {t('dt.bookedBy')}</dt><dd>{booking.booker.fullName}</dd></div>
            {!isBlock && booking.attendees && <div><dt><Users size={16} aria-hidden /> {t('dt.attendees')}</dt><dd>{booking.attendees}</dd></div>}
          </dl>
          {booking.description && <p className="detail-desc">{booking.description}</p>}
          {booking.status === 'CANCELLED' && (
            <Banner tone="info">{booking.cancelReason ? t('dt.cancelledReason', { reason: booking.cancelReason }) : t('dt.cancelledPlain')}</Banner>
          )}
        </div>
      </Modal>

      <Modal
        open={cancelling}
        onClose={() => setCancelling(false)}
        title={isBlock ? t('dt.liftTitle') : t('dt.cancelTitle')}
        footer={
          <>
            <Button variant="ghost" onClick={() => setCancelling(false)} disabled={cancel.isPending}>{t('dt.keep')}</Button>
            <Button variant="danger" loading={cancel.isPending} onClick={() => cancel.mutate()}>{isBlock ? t('dt.yesLift') : t('dt.yesCancel')}</Button>
          </>
        }
      >
        <p className="muted">
          {isBlock
            ? t('dt.liftBody', { room: booking.room.name })
            : t('dt.cancelBody', { title: booking.title, room: booking.room.name, date: formatDate(wallDate(booking.startTime), { day: 'numeric', month: 'short' }), start: wallTime(booking.startTime), end: wallTime(booking.endTime) })}
        </p>
        {booking.seriesId && !isBlock && (
          <div className="radio-group" role="radiogroup" aria-label={t('rec.badge')}>
            {(['ONE', 'FOLLOWING'] as Scope[]).map((s) => (
              <label key={s} className="radio">
                <input type="radio" name="scope" checked={scope === s} onChange={() => setScope(s)} />
                {s === 'ONE' ? t('dt.scopeOne') : t('dt.scopeFollowing')}
              </label>
            ))}
          </div>
        )}
        {!isBlock && (
          <Field label={t('dt.reason')}>
            <textarea rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} placeholder={t('dt.reasonPh')} />
          </Field>
        )}
        {error && <Banner tone="danger">{error}</Banner>}
      </Modal>
    </>
  );
}
