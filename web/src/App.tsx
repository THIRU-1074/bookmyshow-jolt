import { lazy, Suspense, type ReactNode } from 'react';
import { createBrowserRouter, Link, Navigate, RouterProvider, useLocation } from 'react-router-dom';
import type { User } from './api/types';
import { useAuth } from './auth/AuthContext';
import { PageTitle } from './components/bits';
import { Layout } from './components/Layout';
import { EmptyState, LoadingBlock, Skeleton } from './components/States';
import { canUseOwnerDashboard, isAdmin } from './lib/roles';
import { Login, Register } from './pages/Auth';
import { BookingDetail } from './pages/BookingDetail';
import { Checkout } from './pages/Checkout';
import { Home } from './pages/Home';
import { MovieDetail } from './pages/MovieDetail';
import { Movies } from './pages/Movies';
import { MyBookings } from './pages/MyBookings';
import { ShowSeats } from './pages/ShowSeats';

// Staff dashboards are split out so audience pages never download them.
const Admin = lazy(() => import('./pages/admin/Admin').then((m) => ({ default: m.Admin })));
const Owner = lazy(() => import('./pages/Owner').then((m) => ({ default: m.Owner })));

function Pending() {
  return (
    <div className="wrap section">
      <LoadingBlock>
        <Skeleton className="skeleton--title" />
        <Skeleton className="skeleton--card" />
      </LoadingBlock>
    </div>
  );
}

/**
 * Sends anonymous visitors to sign in and visitors without the role home. It waits for the
 * on-load refresh so a reload of a guarded page does not bounce through /login.
 */
function Guard({ allow, children }: { allow?: (u: User) => boolean; children: ReactNode }) {
  const { user, ready } = useAuth();
  const location = useLocation();
  if (!ready) return <Pending />;
  if (!user) return <Navigate to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`} replace />;
  if (allow && !allow(user)) return <Navigate to="/" replace />;
  return <>{children}</>;
}

function NotFound() {
  return (
    <div className="wrap section narrow">
      <PageTitle>Wrong screen</PageTitle>
      <EmptyState title="This page is not showing" art="reel">
        <p>The link may be old or mistyped.</p>
        <Link className="btn" to="/">
          Back to films
        </Link>
      </EmptyState>
    </div>
  );
}

export const routes = [
  {
    element: <Layout />,
    children: [
      { path: '/', element: <Home /> },
      { path: '/movies', element: <Movies /> },
      { path: '/movies/:id', element: <MovieDetail /> },
      { path: '/shows/:id', element: <ShowSeats /> },
      { path: '/login', element: <Login /> },
      { path: '/register', element: <Register /> },
      { path: '/checkout/:id', element: <Guard children={<Checkout />} /> },
      { path: '/bookings', element: <Guard children={<MyBookings />} /> },
      { path: '/bookings/:id', element: <Guard children={<BookingDetail />} /> },
      {
        path: '/owner',
        element: (
          <Guard allow={canUseOwnerDashboard}>
            <Suspense fallback={<Pending />}>
              <Owner />
            </Suspense>
          </Guard>
        ),
      },
      {
        path: '/admin',
        element: (
          <Guard allow={isAdmin}>
            <Suspense fallback={<Pending />}>
              <Admin />
            </Suspense>
          </Guard>
        ),
      },
      { path: '*', element: <NotFound /> },
    ],
  },
];

const router = createBrowserRouter(routes);

export function App() {
  return <RouterProvider router={router} />;
}
