import { useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ArrowRight, ShieldCheck, Train } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/Card';
import { Input } from '@/components/ui/Input';
import { AuthApiError, authApi } from '@/features/auth/api';
import type { RegistrationDetails } from '@/features/auth/types';
import { ROUTES } from '@/lib/constants';

type RegistrationErrors = Partial<Record<keyof RegistrationDetails, string>>;

const emptyFields: RegistrationDetails = {
  username: '',
  fullName: '',
  email: '',
  phoneNumber: '',
  password: '',
};

export default function RegisterPage() {
  const navigate = useNavigate();
  const submitting = useRef(false);
  const [fields, setFields] = useState(emptyFields);
  const [errors, setErrors] = useState<RegistrationErrors>({});
  const [formError, setFormError] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);

  function validate(): RegistrationErrors {
    const nextErrors: RegistrationErrors = {};
    if (!fields.username.trim()) nextErrors.username = 'Enter a username.';
    if (!fields.fullName.trim()) nextErrors.fullName = 'Enter your full name.';
    if (!fields.email.trim()) {
      nextErrors.email = 'Enter your email address.';
    } else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(fields.email.trim())) {
      nextErrors.email = 'Enter a valid email address.';
    }
    if (!fields.password.trim()) nextErrors.password = 'Create a password.';
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
      await authApi.register({
        ...fields,
        username: fields.username.trim(),
        fullName: fields.fullName.trim(),
        email: fields.email.trim(),
        phoneNumber: fields.phoneNumber.trim(),
      });
      navigate(ROUTES.LOGIN, { replace: true, state: { registered: true } });
    } catch (error) {
      setFormError(
        error instanceof AuthApiError
          ? error.message
          : 'Unable to create your account right now. Please try again.',
      );
    } finally {
      submitting.current = false;
      setIsSubmitting(false);
    }
  }

  function updateField(field: keyof RegistrationDetails, value: string) {
    setFields((current) => ({ ...current, [field]: value }));
    setErrors((current) => ({ ...current, [field]: undefined }));
    setFormError('');
  }

  return (
    <section className="relative overflow-hidden bg-surface-base px-4 py-10 sm:px-6 sm:py-14">
      <div className="pointer-events-none absolute inset-x-0 top-0 h-64 bg-gradient-to-b from-teal-50 to-transparent" />
      <div className="relative mx-auto grid max-w-5xl items-center gap-10 lg:grid-cols-[1fr_480px]">
        <div className="hidden lg:block">
          <div className="mb-5 inline-flex h-12 w-12 items-center justify-center rounded-xl bg-navy-700 text-white shadow-card">
            <Train size={23} aria-hidden="true" />
          </div>
          <p className="text-sm font-semibold uppercase tracking-[0.16em] text-teal-700">
            Travel made simpler
          </p>
          <h1 className="mt-3 max-w-lg text-4xl font-bold tracking-tight text-navy-700">
            Start your Railora journey.
          </h1>
          <p className="mt-4 max-w-md text-base leading-relaxed text-ink-muted">
            Create your account to make it easier to plan and manage your railway journeys.
          </p>
          <div className="mt-8 flex items-center gap-2 text-sm text-ink-secondary">
            <ShieldCheck size={17} className="text-teal-600" aria-hidden="true" />
            Your account details stay protected
          </div>
        </div>

        <Card className="w-full shadow-modal">
          <CardHeader className="border-b-0 px-6 pb-0 pt-7 sm:px-8 sm:pt-8">
            <div className="mb-5 flex h-10 w-10 items-center justify-center rounded-lg bg-teal-50 text-teal-700 lg:hidden">
              <Train size={20} aria-hidden="true" />
            </div>
            <CardTitle className="text-2xl">Create your account</CardTitle>
            <CardDescription className="mt-2 text-sm">
              A few details are all you need to get started.
            </CardDescription>
          </CardHeader>
          <CardContent className="px-6 pb-7 pt-6 sm:px-8 sm:pb-8">
            {formError && (
              <p
                role="alert"
                className="mb-5 rounded-lg border border-status-danger-border bg-status-danger-bg px-3.5 py-3 text-sm text-status-danger-text"
              >
                {formError}
              </p>
            )}
            <form onSubmit={handleSubmit} noValidate aria-label="Create account form">
              <div className="space-y-4">
                <Input
                  label="Username"
                  name="username"
                  autoComplete="username"
                  autoFocus
                  required
                  value={fields.username}
                  error={errors.username}
                  onChange={(event) => updateField('username', event.target.value)}
                />
                <Input
                  label="Full name"
                  name="fullName"
                  autoComplete="name"
                  required
                  value={fields.fullName}
                  error={errors.fullName}
                  onChange={(event) => updateField('fullName', event.target.value)}
                />
                <Input
                  label="Email address"
                  name="email"
                  type="email"
                  autoComplete="email"
                  required
                  value={fields.email}
                  error={errors.email}
                  onChange={(event) => updateField('email', event.target.value)}
                />
                <Input
                  label="Phone number"
                  name="phoneNumber"
                  type="tel"
                  autoComplete="tel"
                  helperText="Optional"
                  value={fields.phoneNumber}
                  onChange={(event) => updateField('phoneNumber', event.target.value)}
                />
                <Input
                  label="Password"
                  name="password"
                  type="password"
                  autoComplete="new-password"
                  required
                  value={fields.password}
                  error={errors.password}
                  onChange={(event) => updateField('password', event.target.value)}
                />
                <Button type="submit" variant="accent" size="lg" fullWidth isLoading={isSubmitting}>
                  {isSubmitting ? 'Creating account…' : 'Create account'}
                  {!isSubmitting && <ArrowRight size={17} aria-hidden="true" />}
                </Button>
              </div>
            </form>
            <p className="mt-6 text-center text-sm text-ink-muted">
              Already have an account?{' '}
              <Link
                to={ROUTES.LOGIN}
                className="font-semibold text-teal-700 underline-offset-4 hover:underline focus-visible:rounded-sm"
              >
                Sign in
              </Link>
            </p>
          </CardContent>
        </Card>
      </div>
    </section>
  );
}
