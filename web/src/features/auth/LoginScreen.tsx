import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { useAuthStore } from '@/stores/authStore';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { PrismLogo } from '@/components/shared/PrismLogo';

const loginSchema = z.object({
  email: z.string().min(1, 'Email is required').email('Please enter a valid email'),
  password: z.string().min(6, 'Password must be at least 6 characters'),
});

type LoginForm = z.infer<typeof loginSchema>;

export function LoginScreen() {
  const [serverError, setServerError] = useState('');
  const [loading, setLoading] = useState(false);
  const [googleLoading, setGoogleLoading] = useState(false);
  const login = useAuthStore((s) => s.login);
  const signInWithGoogle = useAuthStore((s) => s.signInWithGoogle);
  const navigate = useNavigate();
  const emailRef = useRef<HTMLInputElement | null>(null);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginForm>({
    defaultValues: { email: '', password: '' },
  });

  // Auto-focus email on mount
  useEffect(() => {
    emailRef.current?.focus();
  }, []);

  const { ref: emailRegRef, ...emailRegProps } = register('email');

  const handleGoogleSignIn = async () => {
    setServerError('');
    setGoogleLoading(true);
    try {
      await signInWithGoogle();
      navigate('/');
    } catch (err) {
      const message =
        (err as { code?: string })?.code === 'auth/popup-closed-by-user'
          ? 'Sign-in cancelled.'
          : 'Google sign-in failed. Please try again.';
      setServerError(message);
    } finally {
      setGoogleLoading(false);
    }
  };

  const onSubmit = async (data: LoginForm) => {
    const result = loginSchema.safeParse(data);
    if (!result.success) return;

    setServerError('');
    setLoading(true);
    try {
      await login(data.email, data.password);
      navigate('/');
    } catch (err) {
      setServerError(
        (err as { response?: { data?: { detail?: string } } })?.response?.data
          ?.detail || 'Invalid credentials. Please try again.',
      );
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-[var(--color-bg-primary)] px-4">
      <div className="w-full max-w-sm">
        {/* Logo */}
        <div className="mb-8 flex flex-col items-center">
          <PrismLogo variant="full" size={48} className="mb-4" />
          <h1 className="text-2xl font-bold text-[var(--color-text-primary)]">
            Welcome Back
          </h1>
          <p className="mt-1 text-sm text-[var(--color-text-secondary)]">
            Sign in to your account
          </p>
        </div>

        {/* Alpha-testing notice */}
        <div
          role="status"
          className="mb-4 rounded-lg border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-sm text-amber-600 dark:text-amber-400"
        >
          <span className="font-semibold">Alpha:</span> This site is in alpha
          testing — features may change or break, and data may be reset.
        </div>

        {/* Google Sign-In (primary) */}
        <Button
          type="button"
          variant="secondary"
          loading={googleLoading}
          className="mb-4 w-full flex items-center justify-center gap-2"
          onClick={handleGoogleSignIn}
        >
          <svg className="h-5 w-5" viewBox="0 0 24 24">
            <path
              d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 0 1-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z"
              fill="#4285F4"
            />
            <path
              d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"
              fill="#34A853"
            />
            <path
              d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"
              fill="#FBBC05"
            />
            <path
              d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"
              fill="#EA4335"
            />
          </svg>
          Sign In With Google
        </Button>

        {/* Divider */}
        <div className="relative mb-4">
          <div className="absolute inset-0 flex items-center">
            <div className="w-full border-t border-[var(--color-border)]" />
          </div>
          <div className="relative flex justify-center text-xs uppercase">
            <span className="bg-[var(--color-bg-primary)] px-2 text-[var(--color-text-secondary)]">
              Or Continue With Email
            </span>
          </div>
        </div>

        {/* Email/Password form (secondary) */}
        <form onSubmit={handleSubmit(onSubmit)} className="flex flex-col gap-4" noValidate>
          <Input
            label="Email"
            type="email"
            placeholder="you@example.com"
            error={errors.email?.message}
            ref={(el) => {
              emailRegRef(el);
              emailRef.current = el;
            }}
            {...emailRegProps}
          />
          <Input
            label="Password"
            type="password"
            placeholder="Enter your password"
            error={errors.password?.message}
            {...register('password')}
          />

          {serverError && (
            <div className="rounded-lg bg-red-500/10 px-3 py-2 text-sm text-red-500">
              {serverError}
            </div>
          )}

          <Button type="submit" loading={loading} className="mt-2 w-full">
            Log In
          </Button>
        </form>

        <p className="mt-6 text-center text-sm text-[var(--color-text-secondary)]">
          Don&apos;t have an account?{' '}
          <Link
            to="/register"
            className="font-medium text-[var(--color-accent)] hover:underline"
          >
            Register
          </Link>
        </p>
      </div>
    </div>
  );
}
