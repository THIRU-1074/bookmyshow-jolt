import { api } from '../api/client';
import type { City, OwnerCinema } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { PageTitle } from '../components/bits';
import { Reports } from '../components/manage/Reports';
import { Screens } from '../components/manage/Screens';
import { Shows } from '../components/manage/Shows';
import { Tabs } from '../components/manage/Tabs';
import { ErrorState, LoadingBlock, Skeleton } from '../components/States';
import { useAsync } from '../hooks/useAsync';
import { isAdmin } from '../lib/roles';

/** Theatre owner dashboard: screens, shows and sales for the caller's linked cinemas only. */
export function Owner() {
  const { user } = useAuth();
  const cinemas = useAsync((signal) => api.get<OwnerCinema[]>('/api/owner/cinemas', undefined, signal), []);
  const cities = useAsync((signal) => api.get<City[]>('/api/cities', undefined, signal), []);
  const loaded = cinemas.data && cities.data;

  return (
    <div className="wrap section">
      <div className="dash-head">
        <PageTitle>My cinemas</PageTitle>
        {cinemas.data && (
          <p className="lede">
            {isAdmin(user) ? 'As an admin you see every cinema. ' : ''}
            {cinemas.data.length === 1 ? cinemas.data[0]?.name : `${cinemas.data.length} cinemas`},{' '}
            {cinemas.data.reduce((n, c) => n + c.screens.length, 0)} screens.
          </p>
        )}
      </div>
      {(cinemas.error || cities.error) && <ErrorState error={cinemas.error ?? cities.error} onRetry={() => (cinemas.reload(), cities.reload())} />}
      {!loaded && !cinemas.error && (
        <LoadingBlock label="Loading your cinemas">
          <Skeleton className="skeleton--card" />
          <Skeleton className="skeleton--card" />
        </LoadingBlock>
      )}
      {loaded && (
        <Tabs
          label="Owner sections"
          tabs={[
            { key: 'sales', label: 'Sales', render: () => <Reports prefix="/api/owner" /> },
            { key: 'shows', label: 'Shows', render: () => <Shows prefix="/api/owner" cinemas={cinemas.data ?? []} cities={cities.data ?? []} /> },
            { key: 'screens', label: 'Screens', render: () => <Screens prefix="/api/owner" cinemas={cinemas.data ?? []} onChanged={cinemas.reload} /> },
          ]}
        />
      )}
    </div>
  );
}
