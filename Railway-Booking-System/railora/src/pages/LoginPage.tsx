import { useRef, useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { ArrowRight, LockKeyhole, ShieldCheck, Train } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/Card';
import { Input } from '@/components/ui/Input';
import { useAuth } from '@/features/auth/AuthContext';
import { AuthApiError } from '@/features/auth/api';
import { ROUTES } from '@/lib/constants';

interface LoginFields {
  username: string;
  password: string;
}

export default function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const submitting = useRef(false);
  const [fields, setFields] = useState<LoginFields>({ username: '', password: '' });
  const [errors, setErrors] = useState<Partial<LoginFields>>({});
  const [formError, setFormError] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const registered = (location.state as { registered?: boolean } | null)?.registered;

  function validate(): Partial<LoginFields> {
    const nextErrors: Partial<LoginFields> = {};
    if (!fields.username.trim()) nextErrors.username = 'Enter your username or email address.';
    if (!fields.password.trim()) nextErrors.password = 'Enter your password.';
    return nextErrors;
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting.current) return;
    const nextErrors = validate();
    setErrors(nextErrors);
    setFormError('');
    if (Object.keys(nextErrors).length > 0) return;

    submitting.current = true;
    setIsSubmitting(true);
    try {
      await login({ username: fields.username.trim(), password: fields.password });
      navigate(ROUTES.HOME, { replace: true });
    } catch (error) {
      setFormError(
        error instanceof AuthApiError
          ? error.message
          : 'Unable to sign in right now. Please try again.',
      );
    } finally {
      submitting.current = false;
      setIsSubmitting(false);
    }
  }

  function updateField(field: keyof LoginFields, value: string) {
    setFields((current) => ({ ...current, [field]: value }));
    setErrors((current) => ({ ...current, [field]: undefined }));
    setFormError('');
  }

  return (
    <section className="relative overflow-hidden bg-surface-base px-4 py-12 sm:px-6 sm:py-16">
      <div className="pointer-events-none absolute inset-x-0 top-0 h-64 bg-gradient-to-b from-teal-50 to-transparent" />
      <div className="relative mx-auto grid max-w-5xl items-center gap-10 lg:grid-cols-[1fr_440px]">
        <div className="hidden lg:block">
          <div className="mb-5 inline-flex h-12 w-12 items-center justify-center rounded-xl bg-navy-700 text-white shadow-card">
            <Train size={23} aria-hidden="true" />
          </div>
          <p className="text-sm font-semibold uppercase tracking-[0.16em] text-teal-700">
            Welcome back
          </p>
          <h1 className="mt-3 max-w-lg text-4xl font-bold tracking-tight text-navy-700">
            Your next journey starts here.
          </h1>
          <p className="mt-4 max-w-md text-base leading-relaxed text-ink-muted">
            Sign in to continue with Railora and keep your railway journeys in one place.
          </p>
          <div className="mt-8 flex items-center gap-2 text-sm text-ink-secondary">
            <ShieldCheck size={17} className="text-teal-600" aria-hidden="true" />
            Secure sign-in to your Railora account
          </div>
        </div>

        <Card className="w-full shadow-modal">
          <CardHeader className="border-b-0 px-6 pb-0 pt-7 sm:px-8 sm:pt-8">
            <div className="mb-5 flex h-10 w-10 items-center justify-center rounded-lg bg-teal-50 text-teal-700 lg:hidden">
              <Train size={20} aria-hidden="true" />
            </div>
            <CardTitle className="text-2xl">Sign in to Railora</CardTitle>
            <CardDescription className="mt-2 text-sm">
              Use your username or email address to access your account.
            </CardDescription>
          </CardHeader>
          <CardContent className="px-6 pb-7 pt-6 sm:px-8 sm:pb-8">
            {registered && (
              <p
                role="status"
                className="mb-5 rounded-lg border border-status-success-border bg-status-success-bg px-3.5 py-3 text-sm text-status-success-text"
              >
                Your account is ready. Sign in to continue.
              </p>
            )}
            {formError && (
              <p
                role="alert"
                className="mb-5 rounded-lg border border-status-danger-border bg-status-danger-bg px-3.5 py-3 text-sm text-status-danger-text"
              >
                {formError}
              </p>
            )}

            <form onSubmit={handleSubmit} noValidate aria-label="Sign in form">
              <div className="space-y-5">
                <Input
                  label="Username or email"
                  name="username"
                  type="text"
                  autoComplete="username"
                  autoFocus
                  required
                  value={fields.username}
                  error={errors.username}
                  onChange={(event) => updateField('username', event.target.value)}
                />
                <Input
                  label="Password"
                  name="password"
                  type="password"
                  autoComplete="current-password"
                  required
                  value={fields.password}
                  error={errors.password}
                  onChange={(event) => updateField('password', event.target.value)}
                />
                <Button type="submit" variant="accent" size="lg" fullWidth isLoading={isSubmitting}>
                  {isSubmitting ? 'Signing in…' : 'Sign in'}
                  {!isSubmitting && <ArrowRight size={17} aria-hidden="true" />}
                </Button>
              </div>
            </form>

            <p className="mt-6 text-center text-sm text-ink-muted">
              New to Railora?{' '}
              <Link
                to={ROUTES.REGISTER}
                className="font-semibold text-teal-700 underline-offset-4 hover:underline focus-visible:rounded-sm"
              >
                Create an account
              </Link>
            </p>
            <p className="mt-5 flex items-center justify-center gap-1.5 text-xs text-ink-muted">
              <LockKeyhole size={13} aria-hidden="true" />
              Your credentials are sent securely to Railora.
            </p>
          </CardContent>
        </Card>
      </div>
    </section>
  );
}
