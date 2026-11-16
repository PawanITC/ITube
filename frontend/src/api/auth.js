const FALLBACK_MESSAGES = {
  400: 'Some details are missing or invalid. Check the form and try again.',
  401: 'Incorrect email or password.',
  409: 'An account with this email already exists.',
};

async function send(url, body) {
  let response;
  try {
    response = await fetch(url, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  } catch {
    throw new Error('Could not reach the server. Is the backend running?');
  }
  if (!response.ok) {
    throw new Error(FALLBACK_MESSAGES[response.status] || `Something went wrong (${response.status}). Please try again.`);
  }
  return response.json();
}

export function login({ email, password }) {
  return send('/api/auth/login', { email, password });
}

export function register({ firstname, lastname, email, password, channelName }) {
  return send('/api/auth/register', { firstname, lastname, email, password, channelName: channelName || null });
}

export async function logout() {
  await fetch('/api/auth/logout', { method: 'POST', credentials: 'include' });
}

/** The signed-in user, or null when nobody is signed in. */
export async function fetchCurrentUser() {
  const response = await fetch('/api/auth/me', { credentials: 'include' });
  if (!response.ok) return null;
  const user = await response.json();
  return user && user.userId ? user : null;
}
