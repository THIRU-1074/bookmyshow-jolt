import '@fontsource-variable/big-shoulders-display/wght';
import '@fontsource/atkinson-hyperlegible/latin-400';
import '@fontsource/atkinson-hyperlegible/latin-700';
import './styles/tokens.css';
import './styles/base.css';
import './styles/pages.css';
import './styles/seats.css';
import './styles/manage.css';
import './styles/print.css';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { AuthProvider } from './auth/AuthContext';
import { ToastProvider } from './components/Toasts';
import { CityProvider } from './hooks/useCity';

createRoot(document.getElementById('root') as HTMLElement).render(
  <StrictMode>
    <AuthProvider>
      <ToastProvider>
        <CityProvider>
          <App />
        </CityProvider>
      </ToastProvider>
    </AuthProvider>
  </StrictMode>,
);
