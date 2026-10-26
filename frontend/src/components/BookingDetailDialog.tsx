import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { CalendarDays, Clock, DoorOpen, Pencil, User as UserIcon, XCircle } from 'lucide-react';
import { api, errorMessage } from '../api/client';
import { useInvalidateBookings } from '../api/hooks';
import type { Booking } from '../api/types';
import { useAuth } from '../lib/auth';
import { durationLabel, formatDate, hasEnded, hasStarted, toMinutes, wallDate, wallTime } from '../lib/time';
import { useToast } from '../lib/toast';
import { BookingDialog } from './BookingDialog';
import { Badge, Banner, Button, Field, Modal } from './ui';

/** Shared rule: who may change a booking, and when. Mirrors the server (which stays the authority). */
export function bookingPermissions(b: Booking, user: { id: string; role: string } | null) {
  const mine = !!user && (user.role === 'ADMIN' || b.booker.id === user.id);
  const active = b.status === 'CONFIRMED' && !hasEnded(b.endTime);
  return { canCancel: mine && active, canEdit: mine && b.status === 'CONFIRMED' && !hasStarted(b.startTime) };
}

export function BookingDetailDialog({ booking, onClose }: { booking: Booking | null; onClose: () => void }) {
  const { user } = useAuth();
  const toast = useToast();
  const invalidate = useInvalidateBookings();
  const [editing, setEditing] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');

  const cancel = useMutation({
    mutationFn: () => api<Booking>(`/bookings/${booking!.id}/cancel`, { method: 'POST', body: { reason: reason.trim() || undefined } }),
    onSuccess: () => {
      invalidate();
      toast.success('Booking cancelled');
      setCancelling(false);
      setReason('');
      onClose();
    },
    onError: (e) => setError(errorMessage(e)),
  });

  if (!booking) return null;
  const { canCancel, canEdit } = bookingPermissions(booking, user);
  const mins = toMinutes(wallTime(booking.endTime)) - toMinutes(wallTime(booking.startTime));

  if (editing) {
    return <BookingDialog open onClose={() => { setEditing(false); onClose(); }} editing={booking} />;
  }

  return (
    <>
      <Modal
        open={!cancelling}
        onClose={onClose}
        title="Booking details"
        footer={
          (canEdit || canCancel) && (
            <>
              {canCancel && <Button variant="danger" icon={<XCircle size={16} />} onClick={() => { setError(''); setCancelling(true); }}>Cancel booking</Button>}
              {canEdit && <Button variant="primary" icon={<Pencil size={16} />} onClick={() => setEditing(true)}>Edit</Button>}
            </>
          )
        }
      >
        <div className="detail">
          <div className="detail-title">
            <h3>{booking.title}</h3>
            {booking.status === 'CANCELLED' ? <Badge tone="danger">Cancelled</Badge> : hasEnded(booking.endTime) ? <Badge>Ended</Badge> : <Badge tone="success">Confirmed</Badge>}
          </div>
          <dl className="detail-list">
            <div><dt><DoorOpen size={16} aria-hidden /> Room</dt><dd>{booking.room.name}</dd></div>
            <div><dt><CalendarDays size={16} aria-hidden /> Date</dt><dd>{formatDate(wallDate(booking.startTime))}</dd></div>
            <div><dt><Clock size={16} aria-hidden /> Time</dt><dd>{wallTime(booking.startTime)} – {wallTime(booking.endTime)} ({durationLabel(mins)})</dd></div>
            <div><dt><UserIcon size={16} aria-hidden /> Booked by</dt><dd>{booking.booker.fullName}</dd></div>
          </dl>
          {booking.description && <p className="detail-desc">{booking.description}</p>}
          {booking.status === 'CANCELLED' && (
            <Banner tone="info">Cancelled{booking.cancelReason ? `: ${booking.cancelReason}` : '.'}</Banner>
          )}
        </div>
      </Modal>

      <Modal
        open={cancelling}
        onClose={() => setCancelling(false)}
        title="Cancel this booking?"
        footer={
          <>
            <Button variant="ghost" onClick={() => setCancelling(false)} disabled={cancel.isPending}>Keep booking</Button>
            <Button variant="danger" loading={cancel.isPending} onClick={() => cancel.mutate()}>Yes, cancel</Button>
          </>
        }
      >
        <p className="muted">
          “{booking.title}” in {booking.room.name} on {formatDate(wallDate(booking.startTime), { day: 'numeric', month: 'short' })},{' '}
          {wallTime(booking.startTime)}–{wallTime(booking.endTime)}. The room becomes free for others right away.
        </p>
        <Field label="Reason (optional)">
          <textarea rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Let others know why" />
        </Field>
        {error && <Banner tone="danger">{error}</Banner>}
      </Modal>
    </>
  );
}
