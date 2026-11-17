import { useEffect, useRef } from 'react';
import { Link, NavLink, Outlet, ScrollRestoration, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { useCity } from '../hooks/useCity';
import { useTheme } from '../hooks/useTheme';
import { canUseOwnerDashboard, isAdmin } from '../lib/roles';
import { Wordmark } from './bits';
import { useToast } from './Toasts';

export function Layout() {
  const { user, client } = useAuth();
  const { city, openPicker } = useCity();
  const [theme, toggleTheme] = useTheme();
  const toast = useToast();
  const navigate = useNavigate();
  const location = useLocation();

  async function signOut() {
    await client.logout().catch(() => {});
    toast.show('Signed out.', 'info');
    navigate('/');
  }

  return (
    <>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <header className="masthead">
        <div className="wrap masthead__inner">
          <Link to="/" className="masthead__brand" aria-label="Houselights, home">
            <Wordmark />
          </Link>
          <nav aria-label="Main" className="masthead__nav">
            <NavLink to="/movies">Films</NavLink>
            {user && <NavLink to="/bookings">My bookings</NavLink>}
            {canUseOwnerDashboard(user) && <NavLink to="/owner">My cinemas</NavLink>}
            {isAdmin(user) && <NavLink to="/admin">Admin</NavLink>}
          </nav>
          <div className="masthead__tools">
            <button type="button" className="city-chip" onClick={openPicker} aria-haspopup="dialog">
              <svg viewBox="0 0 16 16" aria-hidden="true">
                <path d="M8 15s5-5.2 5-9A5 5 0 0 0 3 6c0 3.8 5 9 5 9z" />
                <circle cx="8" cy="6" r="1.8" />
              </svg>
              <span>{city ? city.name : 'Choose city'}</span>
              {city && <span className="visually-hidden">, change city</span>}
            </button>
            <button
              type="button"
              className="icon-btn"
              onClick={toggleTheme}
              aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
            >
              {theme === 'dark' ? (
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <circle cx="12" cy="12" r="4.5" />
                  <path d="M12 2v2.5M12 19.5V22M2 12h2.5M19.5 12H22M4.9 4.9l1.8 1.8M17.3 17.3l1.8 1.8M4.9 19.1l1.8-1.8M17.3 6.7l1.8-1.8" />
                </svg>
              ) : (
                <svg viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z" />
                </svg>
              )}
            </button>
            {user ? (
              <button type="button" className="btn btn--quiet btn--sm" onClick={signOut}>
                Sign out<span className="visually-hidden"> {user.name}</span>
              </button>
            ) : (
              <Link
                className="btn btn--sm"
                to={
                  // On the auth pages keep their own ?next instead of pointing back at /login.
                  /^\/(login|register)$/.test(location.pathname)
                    ? `/login${location.search}`
                    : `/login?next=${encodeURIComponent(location.pathname + location.search)}`
                }
              >
                Sign in
              </Link>
            )}
          </div>
        </div>
      </header>
      <main id="main" className="page" key={location.pathname}>
        <Outlet />
      </main>
      <footer className="colophon">
        <div className="wrap">
          <Wordmark />
          <p>
            A demo cinema box office running on the Jolt HTTP framework. Payments go through a mock gateway, so no real
            money moves.
          </p>
        </div>
      </footer>
      <CityDialog />
      <ScrollRestoration />
    </>
  );
}

function CityDialog() {
  const { cities, city, setCity, pickerOpen, closePicker, error } = useCity();
  const ref = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (pickerOpen && !d.open) d.showModal?.();
    if (!pickerOpen && d.open) d.close();
  }, [pickerOpen]);

  return (
    <dialog ref={ref} className="sheet" aria-labelledby="city-title" onClose={closePicker} onCancel={closePicker}>
      <div className="sheet__body">
        <h2 id="city-title">Where are you watching?</h2>
        {error && <p className="field-error">{error.message}</p>}
        <div className="city-grid">
          {(cities ?? []).map((c) => (
            <button
              key={c.id}
              type="button"
              className="city-tile"
              aria-pressed={city?.id === c.id}
              onClick={() => setCity(c.id)}
            >
              <span className="city-tile__initial" aria-hidden="true">
                {c.name.slice(0, 1)}
              </span>
              {c.name}
            </button>
          ))}
        </div>
        <button type="button" className="btn btn--ghost" onClick={closePicker}>
          Close
        </button>
      </div>
    </dialog>
  );
}
