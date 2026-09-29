import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { applyTheme } from './app/theme';
import { DEFAULT_LOCALE, setLocale } from './lib/i18n/locale';
import './styles/global.css';

// Hiddenjem нарисован в одной, светлой теме: тёмная не входила в концепт, и прежний переключатель
// перекрашивал бы только служебные экраны. Применяется до первой отрисовки.
applyTheme('light');
document.documentElement.lang = DEFAULT_LOCALE;
setLocale(DEFAULT_LOCALE);

const container = document.getElementById('root');
if (!container) {
  throw new Error('Не найден корневой элемент #root');
}

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
