import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { AppLayout } from './components/AppLayout';
import { ErrorState, Spinner } from './components/ui';
import { useConfig } from './api/hooks';
import { useAuth } from './lib/auth';
import { AdminBookingsPage } from './pages/AdminBookingsPage';
import { AdminRoomsPage } from './pages/AdminRoomsPage';
import { AdminUsersPage } from './pages/AdminUsersPage';
import { FindRoomPage } from './pages/FindRoomPage';
import { LoginPage } from './pages/LoginPage';
import { MyBookingsPage } from './pages/MyBookingsPage';
import { SchedulePage } from './pages/SchedulePage';

function RequireAuth({ admin }: { admin?: boolean }) {
  const { user, loading } = useAuth();
  const location = useLocation();
  const cfg = useConfig(!!user); // never call the API before we know who is signed in
  if (loading) return <div className="center-screen"><Spinner size={28} /></div>;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  if (admin && user.role !== 'ADMIN') return <Navigate to="/" replace />;
  if (cfg.isPending) return <div className="center-screen"><Spinner size={28} /></div>;
  if (cfg.isError) return <div className="center-screen"><ErrorState error={cfg.error} onRetry={() => cfg.refetch()} /></div>;
  return <AppLayout />;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireAuth />}>
        <Route path="/" element={<SchedulePage />} />
        <Route path="/find" element={<FindRoomPage />} />
        <Route path="/my" element={<MyBookingsPage />} />
      </Route>
      <Route element={<RequireAuth admin />}>
        <Route path="/admin/rooms" element={<AdminRoomsPage />} />
        <Route path="/admin/bookings" element={<AdminBookingsPage />} />
        <Route path="/admin/users" element={<AdminUsersPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
