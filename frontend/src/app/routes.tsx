/**
 * Таблица маршрутов Hiddenjem.
 *
 * Три раздела — Радар, Новый анализ, Отчёты — и то, что из них открывается: ход анализа, отчёт с
 * тремя вкладками, карточка темы. Прежние адреса (`/research`, `/history`, `/topics`, `/saved`)
 * ведут на новые места: ссылки из выгрузок и закладок не должны превращаться в 404.
 *
 * Входа нет, одна организация, API открыт — поэтому таблица одна и без охраны маршрутов. Прежние
 * адреса входа, профиля и администрирования ведут в «не найдено»: этих экранов больше нет.
 *
 * Служебные разделы (источники, словарь направлений) загружаются лениво: их открывают редко, а
 * контейнер фронтенда ограничен 64 Mi.
 */
import { Suspense, lazy } from 'react';
import { Navigate, Route, Routes, useLocation, useParams } from 'react-router-dom';
import { CenteredSpinner } from '@/components/Skeleton';
import { RadarPage } from '@/features/radar/RadarPage';
import { NewAnalysisPage } from '@/features/research/NewAnalysisPage';
import { ReportsPage } from '@/features/research/ReportsPage';
import { RunPage } from '@/features/research/RunPage';
import { ReportPage } from '@/features/report/ReportPage';
import { TopicPage } from '@/features/report/TopicPage';
import { AppLayout } from './AppLayout';
import { NotFoundPage } from './NotFoundPage';

const SourcesPage = lazy(() =>
  import('@/features/sources/SourcesPage').then((module) => ({ default: module.SourcesPage })),
);
const AdminLexiconQueuePage = lazy(() =>
  import('@/features/admin/AdminLexiconQueuePage').then((module) => ({
    default: module.AdminLexiconQueuePage,
  })),
);

function Lazy({ children }: { children: React.ReactNode }): React.ReactElement {
  return <Suspense fallback={<CenteredSpinner label="Загрузка…" />}>{children}</Suspense>;
}

/** `/research?query=…` → `/new?query=…`: параметры повтора переезжают вместе с адресом. */
function ToNewAnalysis(): React.ReactElement {
  const { search } = useLocation();
  return <Navigate to={`/new${search}`} replace />;
}

function ToRun(): React.ReactElement {
  const { requestId = '' } = useParams();
  return <Navigate to={`/runs/${requestId}`} replace />;
}

export function AppRoutes(): React.ReactElement {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<Navigate to="/radar" replace />} />
        <Route path="/radar" element={<RadarPage />} />
        <Route path="/new" element={<NewAnalysisPage />} />
        <Route path="/runs/:requestId" element={<RunPage />} />
        <Route path="/reports" element={<ReportsPage />} />
        <Route path="/reports/:reportId" element={<ReportPage />} />
        <Route path="/reports/:reportId/excluded" element={<ReportPage />} />
        <Route path="/reports/:reportId/changes" element={<ReportPage />} />
        <Route path="/reports/:reportId/trends/:trendKey" element={<TopicPage />} />
        <Route path="/research" element={<ToNewAnalysis />} />
        <Route path="/research/:requestId" element={<ToRun />} />
        <Route path="/history" element={<Navigate to="/reports" replace />} />
        <Route path="/topics" element={<Navigate to="/reports" replace />} />
        <Route path="/saved" element={<Navigate to="/radar" replace />} />
        <Route
          path="/sources"
          element={
            <Lazy>
              <SourcesPage />
            </Lazy>
          }
        />
        <Route
          path="/admin/lexicon"
          element={
            <Lazy>
              <AdminLexiconQueuePage />
            </Lazy>
          }
        />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
