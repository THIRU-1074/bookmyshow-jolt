// "/login" and "/register": forms with inline validation from 422 `errors` (SPEC section 8).
import { ApiError, login, register } from '../api.js';
import { navigate } from '../router.js';
import { h, pageHeading, toast } from '../util.js';

function safeNext(query) {
  const next = query.get('next');
  // Same-origin paths only, so a crafted link cannot bounce the user to another site.
  return next && next.startsWith('/') && !next.startsWith('//') ? next : '/movies';
}

export function render({ main, query }) {
  const signUp = location.pathname === '/register';
  const next = safeNext(query);
  const errorsFor = {};

  function field(name, label, type, extra = {}) {
    const id = `f-${name}`;
    const err = h('p', { class: 'field-error', id: `${id}-err`, hidden: true });
    const input = h('input', { id, name, type, required: true, autocomplete: extra.autocomplete, minlength: extra.minlength, maxlength: extra.maxlength });
    errorsFor[name] = { input, err };
    return h('div', null, h('label', { for: id }, label), input, extra.hint ? h('p', { class: 'field-hint' }, extra.hint) : null, err);
  }

  function showErrors(map) {
    let first = null;
    for (const [name, { input, err }] of Object.entries(errorsFor)) {
      const message = map[name];
      err.hidden = !message;
      err.textContent = message || '';
      if (message) {
        input.setAttribute('aria-invalid', 'true');
        input.setAttribute('aria-describedby', err.id);
        first ??= input;
      } else {
        input.removeAttribute('aria-invalid');
        input.removeAttribute('aria-describedby');
      }
    }
    first?.focus();
  }

  const banner = h('p', { class: 'alert alert-error', role: 'alert', hidden: true });
  const submit = h('button', { type: 'submit', class: 'button' }, signUp ? 'Create account' : 'Sign in');

  const form = h('form', { class: 'form', novalidate: true },
    banner,
    signUp ? field('name', 'Name', 'text', { autocomplete: 'name', maxlength: '80' }) : null,
    field('email', 'Email', 'email', { autocomplete: 'email' }),
    field('password', 'Password', 'password', {
      autocomplete: signUp ? 'new-password' : 'current-password',
      minlength: signUp ? '10' : undefined,
      maxlength: '128',
      hint: signUp ? 'At least 10 characters.' : undefined,
    }),
    submit);

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    banner.hidden = true;
    showErrors({});
    const data = Object.fromEntries(new FormData(form));
    const local = {};
    if (signUp && !data.name?.trim()) local.name = 'Enter your name.';
    if (!data.email?.includes('@')) local.email = 'Enter a valid email address.';
    if (!data.password) local.password = 'Enter your password.';
    else if (signUp && data.password.length < 10) local.password = 'Use at least 10 characters.';
    if (Object.keys(local).length) return showErrors(local);

    submit.disabled = true;
    try {
      if (signUp) await register(data.name.trim(), data.email.trim(), data.password);
      else await login(data.email.trim(), data.password);
      toast(signUp ? 'Account created.' : 'Signed in.');
      navigate(next, { replace: true });
    } catch (err) {
      if (err instanceof ApiError && err.status === 422) {
        showErrors(err.fieldErrors);
        if (!Object.keys(err.fieldErrors).length) {
          banner.textContent = err.message;
          banner.hidden = false;
        }
      } else {
        banner.textContent = err.message;
        banner.hidden = false;
        banner.focus?.();
      }
    } finally {
      submit.disabled = false;
    }
  });

  main.append(
    pageHeading(signUp ? 'Create your account' : 'Sign in'),
    form,
    h('p', null, signUp ? 'Already registered? ' : 'New here? ',
      h('a', { href: `${signUp ? '/login' : '/register'}${next !== '/movies' ? `?next=${encodeURIComponent(next)}` : ''}` }, signUp ? 'Sign in' : 'Create an account')));
}
