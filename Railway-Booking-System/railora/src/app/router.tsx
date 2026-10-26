import { Routes, Route } from 'react-router-dom';
import HomePage from '@/pages/HomePage';
import TrainSearchPage from '@/pages/TrainSearchPage';
import NotFoundPage from '@/pages/NotFoundPage';
import { ROUTES } from '@/lib/constants';

/**
 * Central application router.
 * All feature routes are registered here.
 */
export default function AppRouter() {
  return (
    <Routes>
      <Route path={ROUTES.HOME} element={<HomePage />} />
      <Route path={ROUTES.SEARCH} element={<TrainSearchPage />} />
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
