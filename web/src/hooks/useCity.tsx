import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api } from '../api/client';
import type { City } from '../api/types';
import { DEFAULT_ZONE } from '../lib/format';
import { storage } from '../lib/storage';

const KEY = 'bms.city';

interface CityValue {
  cities: City[] | undefined;
  city: City | null;
  zone: string;
  setCity: (id: string) => void;
  pickerOpen: boolean;
  openPicker: () => void;
  closePicker: () => void;
  error: Error | undefined;
}

const CityContext = createContext<CityValue | null>(null);

export function CityProvider({ children }: { children: ReactNode }) {
  const [cities, setCities] = useState<City[]>();
  const [error, setError] = useState<Error>();
  const [id, setId] = useState<string | null>(() => storage.get(KEY));
  const [pickerOpen, setPickerOpen] = useState(false);

  useEffect(() => {
    api
      .get<City[]>('/api/cities')
      .then(setCities)
      .catch((e: Error) => setError(e));
  }, []);

  const setCity = useCallback((next: string) => {
    storage.set(KEY, next);
    setId(next);
    setPickerOpen(false);
  }, []);

  const city = useMemo(() => cities?.find((c) => c.id === id) ?? null, [cities, id]);
  const value = useMemo<CityValue>(
    () => ({
      cities,
      city,
      zone: city?.timeZone ?? DEFAULT_ZONE,
      setCity,
      pickerOpen,
      openPicker: () => setPickerOpen(true),
      closePicker: () => setPickerOpen(false),
      error,
    }),
    [cities, city, setCity, pickerOpen, error],
  );
  return <CityContext.Provider value={value}>{children}</CityContext.Provider>;
}

export function useCity(): CityValue {
  const v = useContext(CityContext);
  if (!v) throw new Error('useCity outside CityProvider');
  return v;
}
