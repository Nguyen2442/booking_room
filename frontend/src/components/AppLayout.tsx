import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, NavLink, Outlet } from 'react-router-dom';
import { BellRing, CalendarCheck, CalendarDays, DoorOpen, ListChecks, LogOut, Search, Ticket, UserCircle, Users, Wrench, X } from 'lucide-react';
import { api } from '../api/client';
import type { Booking, Page } from '../api/types';
import { useAuth } from '../lib/auth';
import { LanguageSwitch, useI18n } from '../lib/i18n';
import type { Key } from '../lib/messages';
import { Badge, Button } from './ui';

type NavItem = { to: string; label: Key; icon: typeof CalendarDays; end?: boolean };
const main: NavItem[] = [
  { to: '/', label: 'nav.schedule', icon: CalendarDays, end: true },
  { to: '/find', label: 'nav.find', icon: Search },
  { to: '/my', label: 'nav.my', icon: Ticket },
];
const admin: NavItem[] = [
  { to: '/admin/rooms', label: 'nav.rooms', icon: DoorOpen },
  { to: '/admin/bookings', label: 'nav.allBookings', icon: ListChecks },
  { to: '/admin/blocks', label: 'nav.maintenance', icon: Wrench },
  { to: '/admin/users', label: 'nav.users', icon: Users },
];

/**
 * There is no email: when an administrator cancels someone's upcoming booking (room blocked, account closed...) the
 * owner would otherwise find out by turning up at a room that is no longer theirs. Show it, once, until dismissed.
 */
function CancelledNotice() {
  const { user } = useAuth();
  const { tn, t } = useI18n();
  const storageKey = `booking.cancelNoticeSeen.${user?.id}`;
  const [seen, setSeen] = useState(() => {
    try { return localStorage.getItem(storageKey) ?? '1970-01-01T00:00:00Z'; } catch { return '1970-01-01T00:00:00Z'; }
  });
  const q = useQuery({
    queryKey: ['bk', 'mine', 'cancel-notice'],
    queryFn: () => api<Page<Booking>>('/bookings/my', {
      query: { status: 'CANCELLED', from: new Date().toISOString(), sort: 'startTime,asc', size: 20 },
    }),
    refetchInterval: 60_000,
  });
  const fresh = (q.data?.content ?? []).filter((b) => b.cancelledByAnother && b.cancelledAt && Date.parse(b.cancelledAt) > Date.parse(seen));
  if (!fresh.length) return null;
  const dismiss = () => {
    const now = new Date().toISOString();
    try { localStorage.setItem(storageKey, now); } catch { /* ignore */ }
    setSeen(now);
  };
  return (
    <div className="notice" role="status">
      <BellRing size={18} aria-hidden />
      <span>{tn('notice.cancelled', fresh.length)}</span>
      <Link className="link" to="/my?tab=cancelled" onClick={dismiss}>{t('notice.view')}</Link>
      <button className="icon-btn" aria-label={t('notice.dismiss')} onClick={dismiss}><X size={16} /></button>
    </div>
  );
}

export function AppLayout() {
  const { user, logout } = useAuth();
  const { t } = useI18n();
  const links = user?.role === 'ADMIN' ? [...main, ...admin] : main;
  return (
    <div className="shell">
      <a className="skip" href="#content">{t('nav.skip')}</a>
      <header className="topbar">
        <div className="brand"><span className="brand-mark" aria-hidden><CalendarCheck size={18} /></span> <span>{t('app.name')}</span></div>
        <nav className="nav" aria-label={t('nav.main')}>
          {links.map(({ to, label, icon: Icon, end }) => (
            <NavLink key={to} to={to} end={end} title={t(label)} className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
              <Icon size={18} aria-hidden /> <span>{t(label)}</span>
            </NavLink>
          ))}
        </nav>
        <div className="user">
          <LanguageSwitch />
          <NavLink to="/account" className={({ isActive }) => `user-meta user-link ${isActive ? 'active' : ''}`} aria-label={t('nav.account')}>
            <UserCircle size={20} aria-hidden />
            <span className="user-name">{user?.fullName}</span>
            {user?.role === 'ADMIN' && <Badge tone="accent">{t('role.admin')}</Badge>}
          </NavLink>
          <Button variant="ghost" icon={<LogOut size={16} />} onClick={logout} aria-label={t('common.signOut')}><span className="hide-sm">{t('common.signOut')}</span></Button>
        </div>
      </header>
      <main id="content" className="content"><CancelledNotice /><Outlet /></main>
    </div>
  );
}
