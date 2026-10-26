import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Plus, Wrench } from 'lucide-react';
import { api, ApiError } from '../api/client';
import { useConfig, useInvalidateBookings, useRooms } from '../api/hooks';
import type { BlockConflict, BlockResult, Booking, Page } from '../api/types';
import { Banner, Button, ConfirmDialog, EmptyState, ErrorState, Field, Modal, PageHeader, Pagination, Skeleton } from '../components/ui';
import { useI18n } from '../lib/i18n';
import { addDays, formatDate, fromMinutes, nowMinutesIn, todayIn, wallDate, wallTime, zonedToIso } from '../lib/time';
import { useToast } from '../lib/toast';

const period = (b: Booking) => {
  const sd = wallDate(b.startTime), ed = wallDate(b.endTime);
  const day = (d: string) => formatDate(d, { weekday: 'short', day: 'numeric', month: 'short' });
  return sd === ed ? `${day(sd)} · ${wallTime(b.startTime)}–${wallTime(b.endTime)}` : `${day(sd)} ${wallTime(b.startTime)} → ${day(ed)} ${wallTime(b.endTime)}`;
};

export function AdminBlocksPage() {
  const { t, err } = useI18n();
  const toast = useToast();
  const invalidate = useInvalidateBookings();
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);
  const [lifting, setLifting] = useState<Booking | null>(null);

  const blocks = useQuery({
    queryKey: ['bk', 'blocks', page],
    queryFn: () => api<Page<Booking>>('/bookings', {
      query: { kind: 'BLOCK', status: 'CONFIRMED', from: new Date().toISOString(), sort: 'startTime,asc', page, size: 15 },
    }),
    placeholderData: (prev) => prev,
  });

  const lift = useMutation({
    mutationFn: (b: Booking) => api(`/bookings/${b.id}/cancel`, { method: 'POST' }),
    onSuccess: () => { toast.success(t('dt.lifted')); setLifting(null); invalidate(); },
    onError: (e) => { toast.error(err(e)); setLifting(null); },
  });

  return (
    <>
      <PageHeader title={t('mt.title')} subtitle={t('mt.subtitle')} actions={<Button variant="primary" icon={<Plus size={18} />} onClick={() => setCreating(true)}>{t('mt.add')}</Button>} />

      {blocks.isPending && <div className="card stack">{Array.from({ length: 3 }, (_, i) => <Skeleton key={i} h={36} />)}</div>}
      {blocks.isError && <ErrorState error={blocks.error} onRetry={() => blocks.refetch()} />}
      {blocks.data && blocks.data.content.length === 0 && <EmptyState icon={<Wrench size={28} />} title={t('mt.emptyTitle')}>{t('mt.emptyHelp')}</EmptyState>}
      {blocks.data && blocks.data.content.length > 0 && (
        <div className="card table-wrap">
          <table className="table">
            <thead><tr><th>{t('common.room')}</th><th>{t('mt.colPeriod')}</th><th>{t('mt.colReason')}</th><th><span className="sr-only">{t('common.actions')}</span></th></tr></thead>
            <tbody>
              {blocks.data.content.map((b) => (
                <tr key={b.id}>
                  <td><strong>{b.room.name}</strong></td>
                  <td>{period(b)}</td>
                  <td>{b.title}</td>
                  <td className="actions"><Button variant="ghost" onClick={() => setLifting(b)}>{t('mt.lift')}</Button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <Pagination page={blocks.data?.page ?? 0} totalPages={blocks.data?.totalPages ?? 0} onChange={setPage} />

      <Modal open={creating} onClose={() => setCreating(false)} title={t('mt.add')}>
        {creating && <BlockForm onClose={() => setCreating(false)} onDone={invalidate} />}
      </Modal>
      <ConfirmDialog
        open={!!lifting} danger title={t('dt.liftTitle')} message={lifting ? t('dt.liftBody', { room: lifting.room.name }) : ''}
        confirmLabel={t('dt.yesLift')} loading={lift.isPending}
        onConfirm={() => lifting && lift.mutate(lifting)} onCancel={() => setLifting(null)}
      />
    </>
  );
}

function BlockForm({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const cfg = useConfig().data!;
  const { t, err } = useI18n();
  const toast = useToast();
  const rooms = useRooms();
  const today = todayIn(cfg.timezone);

  const defaults = useMemo(() => {
    const next = Math.ceil((nowMinutesIn(cfg.timezone) + 1) / cfg.slotMinutes) * cfg.slotMinutes;
    const startDate = next + 120 < 24 * 60 ? today : addDays(today, 1);
    const s = startDate === today ? next : 9 * 60;
    return { startDate, start: fromMinutes(s), end: fromMinutes(Math.min(s + 120, 23 * 60 + 45)) };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const [roomId, setRoomId] = useState('');
  const [startDate, setStartDate] = useState(defaults.startDate);
  const [startTime, setStartTime] = useState(defaults.start);
  const [endDate, setEndDate] = useState(defaults.startDate);
  const [endTime, setEndTime] = useState(defaults.end);
  const [reason, setReason] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [serverError, setServerError] = useState('');
  const [conflicts, setConflicts] = useState<BlockConflict[]>([]);
  const alertRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (conflicts.length || serverError) alertRef.current?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }, [conflicts, serverError]);

  const times = useMemo(() => {
    const out: string[] = [];
    for (let m = 0; m < 24 * 60; m += cfg.slotMinutes) out.push(fromMinutes(m));
    return out;
  }, [cfg.slotMinutes]);
  const effectiveRoom = roomId || rooms.data?.content[0]?.id || '';

  const create = useMutation<BlockResult, unknown, boolean>({
    mutationFn: (cancelConflicting) => api<BlockResult>('/admin/blocks', {
      method: 'POST',
      body: {
        roomId: effectiveRoom, reason: reason.trim(), cancelConflicting,
        startTime: zonedToIso(startDate, startTime, cfg.timezone), endTime: zonedToIso(endDate, endTime, cfg.timezone),
      },
    }),
    onSuccess: (res) => {
      onDone();
      toast.success(res.cancelledBookings.length ? t('mt.createdCancelled', { n: res.cancelledBookings.length }) : t('mt.created'));
      onClose();
    },
    onError: (e) => {
      if (e instanceof ApiError && e.code === 'BLOCK_CONFLICTS_WITH_BOOKINGS') setConflicts(e.problem.conflicts ?? []);
      else setServerError(err(e));
    },
  });

  function submit(e: React.FormEvent, cancelConflicting = false) {
    e.preventDefault();
    const n: Record<string, string> = {};
    if (!reason.trim()) n.reason = t('mt.reasonRequired');
    if (zonedToIso(endDate, endTime, cfg.timezone) <= zonedToIso(startDate, startTime, cfg.timezone)) n.end = t('mt.endAfter');
    setErrors(n); setServerError(''); setConflicts([]);
    if (!Object.keys(n).length) create.mutate(cancelConflicting);
  }

  return (
    <form className="form" onSubmit={submit} noValidate>
      <Field label={t('common.room')}>
        <select value={effectiveRoom} onChange={(e) => setRoomId(e.target.value)} disabled={rooms.isPending}>
          {rooms.data?.content.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
        </select>
      </Field>
      <div className="grid-2">
        <Field label={t('mt.startDate')}><input type="date" value={startDate} min={today} onChange={(e) => { if (!e.target.value) return; setStartDate(e.target.value); if (e.target.value > endDate) setEndDate(e.target.value); }} /></Field>
        <Field label={t('mt.startTime')}><select value={startTime} onChange={(e) => setStartTime(e.target.value)}>{times.map((x) => <option key={x}>{x}</option>)}</select></Field>
      </div>
      <div className="grid-2">
        <Field label={t('mt.endDate')}><input type="date" value={endDate} min={startDate} onChange={(e) => e.target.value && setEndDate(e.target.value)} /></Field>
        <Field label={t('mt.endTime')} error={errors.end}><select value={endTime} onChange={(e) => setEndTime(e.target.value)}>{times.map((x) => <option key={x}>{x}</option>)}</select></Field>
      </div>
      <Field label={t('mt.reason')} error={errors.reason}><input value={reason} maxLength={200} onChange={(e) => setReason(e.target.value)} placeholder={t('mt.reasonPh')} /></Field>

      {serverError && <Banner tone="danger">{serverError}</Banner>}
      {conflicts.length > 0 && (
        <div ref={alertRef} className="suggest">
          <Banner tone="warning">{t('mt.conflictTitle', { n: conflicts.length })}</Banner>
          <ul className="conflict-list">
            {conflicts.map((c) => (
              <li key={c.id}><strong>{c.title}</strong> · {c.booker} · {formatDate(wallDate(c.startTime), { weekday: 'short', day: 'numeric', month: 'short' })} {wallTime(c.startTime)}–{wallTime(c.endTime)}</li>
            ))}
          </ul>
          <p className="muted small">{t('mt.cancelNote')}</p>
          <Button variant="danger" loading={create.isPending} onClick={(e) => submit(e as unknown as React.FormEvent, true)}>{t('mt.cancelAndBlock')}</Button>
        </div>
      )}
      <div className="form-actions">
        <Button variant="ghost" onClick={onClose} disabled={create.isPending}>{t('common.dismiss')}</Button>
        <Button variant="primary" type="submit" loading={create.isPending && conflicts.length === 0}>{t('mt.submit')}</Button>
      </div>
    </form>
  );
}
