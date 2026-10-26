import { NavLink, Outlet } from 'react-router-dom';
import { CalendarCheck, CalendarDays, DoorOpen, LogOut, Search, Ticket, Users, ListChecks } from 'lucide-react';
import { useAuth } from '../lib/auth';
import { Badge, Button } from './ui';

type NavItem = { to: string; label: string; icon: typeof CalendarDays; end?: boolean };
const main: NavItem[] = [
  { to: '/', label: 'Schedule', icon: CalendarDays, end: true },
  { to: '/find', label: 'Find a room', icon: Search },
  { to: '/my', label: 'My bookings', icon: Ticket },
];
const admin: NavItem[] = [
  { to: '/admin/rooms', label: 'Rooms', icon: DoorOpen },
  { to: '/admin/bookings', label: 'All bookings', icon: ListChecks },
  { to: '/admin/users', label: 'Users', icon: Users },
];

export function AppLayout() {
  const { user, logout } = useAuth();
  const links = user?.role === 'ADMIN' ? [...main, ...admin] : main;
  return (
    <div className="shell">
      <a className="skip" href="#content">Skip to content</a>
      <header className="topbar">
        <div className="brand"><span className="brand-mark" aria-hidden><CalendarCheck size={18} /></span> Room Booking</div>
        <nav className="nav" aria-label="Main">
          {links.map(({ to, label, icon: Icon, end }) => (
            <NavLink key={to} to={to} end={end} className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
              <Icon size={18} aria-hidden /> <span>{label}</span>
            </NavLink>
          ))}
        </nav>
        <div className="user">
          <div className="user-meta">
            <span className="user-name">{user?.fullName}</span>
            {user?.role === 'ADMIN' && <Badge tone="accent">Admin</Badge>}
          </div>
          <Button variant="ghost" icon={<LogOut size={16} />} onClick={logout} aria-label="Sign out"><span className="hide-sm">Sign out</span></Button>
        </div>
      </header>
      <main id="content" className="content"><Outlet /></main>
    </div>
  );
}
