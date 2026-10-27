import { Routes, Route } from 'react-router-dom';
import HomePage from '@/pages/HomePage';
import TrainSearchPage from '@/pages/TrainSearchPage';
import BookingsPage from '@/pages/BookingsPage';
import WaitingRoomPage from '@/pages/WaitingRoomPage';
import NotFoundPage from '@/pages/NotFoundPage';
import LoginPage from '@/pages/LoginPage';
import RegisterPage from '@/pages/RegisterPage';
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
      <Route path={ROUTES.BOOKINGS} element={<BookingsPage />} />
      <Route path={ROUTES.WAITING_ROOM} element={<WaitingRoomPage />} />
      <Route path={ROUTES.LOGIN} element={<LoginPage />} />
      <Route path={ROUTES.REGISTER} element={<RegisterPage />} />
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
