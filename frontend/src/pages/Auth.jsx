import { useEffect, useState } from 'react';
import { login, register } from '../api/auth';
import { cognitoErrorMessage, fetchSignInProviders, signInWithCognito } from '../api/session';
import Icon from '../components/Icon';
import './Auth.css';

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

function validate(mode, form) {
  const errors = {};
  if (mode === 'register') {
    if (!form.firstname.trim()) errors.firstname = 'Enter your first name.';
    if (!form.lastname.trim()) errors.lastname = 'Enter your last name.';
  }
  if (!EMAIL_PATTERN.test(form.email.trim())) errors.email = 'Enter a valid email address.';
  if (mode === 'register') {
    if (form.password.length < 8) errors.password = 'Use at least 8 characters.';
    else if (form.password.length > 72) errors.password = 'Use 72 characters or fewer.';
    if (form.confirmPassword !== form.password) errors.confirmPassword = 'Passwords don’t match.';
  } else if (!form.password) {
    errors.password = 'Enter your password.';
  }
  return errors;
}

const EMPTY_FORM = { firstname: '', lastname: '', email: '', password: '', confirmPassword: '', channelName: '' };

export default function Auth({ mode, onSignedIn, onSwitchMode, onHome }) {
  const [form, setForm] = useState(EMPTY_FORM);
  const [errors, setErrors] = useState({});
  const [serverError, setServerError] = useState(() => cognitoErrorMessage());
  const [submitting, setSubmitting] = useState(false);
  const [cognitoAvailable, setCognitoAvailable] = useState(false);
  const isRegister = mode === 'register';

  useEffect(() => {
    let cancelled = false;
    fetchSignInProviders().then(providers => { if (!cancelled) setCognitoAvailable(Boolean(providers?.cognito)); });
    return () => { cancelled = true; };
  }, []);

  function update(field) {
    return event => {
      setForm(current => ({ ...current, [field]: event.target.value }));
      setErrors(current => (current[field] ? { ...current, [field]: undefined } : current));
    };
  }

  async function submit(event) {
    event.preventDefault();
    const found = validate(mode, form);
    setErrors(found);
    setServerError('');
    if (Object.values(found).some(Boolean)) return;

    setSubmitting(true);
    try {
      const user = isRegister
        ? await register({ ...form, email: form.email.trim(), channelName: form.channelName.trim() })
        : await login({ email: form.email.trim(), password: form.password });
      onSignedIn(user);
    } catch (error) {
      setServerError(error.message);
    } finally {
      setSubmitting(false);
    }
  }

  function switchMode(event) {
    event.preventDefault();
    setErrors({});
    setServerError('');
    setForm(current => ({ ...EMPTY_FORM, email: current.email }));
    onSwitchMode(isRegister ? 'login' : 'register');
  }

  function field(name, label, props = {}) {
    const errorId = `auth-${name}-error`;
    return (
      <div className="auth-field">
        <label htmlFor={`auth-${name}`}>{label}</label>
        <input
          id={`auth-${name}`}
          value={form[name]}
          onChange={update(name)}
          aria-invalid={errors[name] ? 'true' : undefined}
          aria-describedby={errors[name] ? errorId : undefined}
          {...props}
        />
        {errors[name] && <p className="auth-field-error" id={errorId}>{errors[name]}</p>}
      </div>
    );
  }

  return (
    <main className="auth-main">
      <div className="auth-card">
        <a className="auth-brand" href="/" onClick={event => { event.preventDefault(); onHome(); }} aria-label="ITube home">
          <span className="brand-mark"><Icon name="play" size={19} /></span><strong>ITube</strong>
        </a>
        <h1>{isRegister ? 'Create your account' : 'Sign in'}</h1>
        <p className="auth-subtitle">{isRegister ? 'Get a channel, like videos and keep your watch history.' : 'Welcome back. Continue to ITube.'}</p>

        <form className="auth-form" onSubmit={submit} noValidate>
          {isRegister && (
            <div className="auth-row">
              {field('firstname', 'First name', { autoComplete: 'given-name', maxLength: 100 })}
              {field('lastname', 'Last name', { autoComplete: 'family-name', maxLength: 100 })}
            </div>
          )}
          {field('email', 'Email', { type: 'email', autoComplete: 'email', maxLength: 320 })}
          {field('password', 'Password', { type: 'password', autoComplete: isRegister ? 'new-password' : 'current-password' })}
          {isRegister && field('confirmPassword', 'Confirm password', { type: 'password', autoComplete: 'new-password' })}
          {isRegister && field('channelName', 'Channel name (optional)', { maxLength: 255, placeholder: 'Defaults to your name' })}

          {serverError && <p className="auth-server-error" role="alert">{serverError}</p>}

          <button className="auth-submit" type="submit" disabled={submitting}>
            {submitting ? (isRegister ? 'Creating account…' : 'Signing in…') : (isRegister ? 'Create account' : 'Sign in')}
          </button>
        </form>

        {cognitoAvailable && (
          <>
            <div className="auth-divider" role="separator"><span>or</span></div>
            <button className="auth-provider" type="button" onClick={signInWithCognito}>
              Continue with Cognito
            </button>
          </>
        )}

        <p className="auth-switch">
          {isRegister ? 'Already have an account? ' : 'New to ITube? '}
          <a href={isRegister ? '/login' : '/register'} onClick={switchMode}>{isRegister ? 'Sign in' : 'Create an account'}</a>
        </p>
      </div>
    </main>
  );
}
