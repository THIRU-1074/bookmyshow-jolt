import { h, pageHeading } from '../util.js';

export function render({ main }) {
  main.append(
    pageHeading('Page not found'),
    h('p', null, 'That page does not exist.'),
    h('a', { href: '/movies', class: 'button' }, 'Browse movies'));
}
