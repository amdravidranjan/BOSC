// Hash routing: location.hash -> show(view). Each view's init runs once; enter runs every time.
import { $ } from "./util.js";

const VIEWS = ["home", "caller", "bosc", "pay", "summary"];
const hooks = {};
export const inited = new Set();

export function onView(view, { init, enter } = {}) { hooks[view] = { init, enter }; }

export function ensureInit(view) {
  if (inited.has(view)) return;
  inited.add(view);
  hooks[view]?.init?.();
}

export function show(view) {
  if (!VIEWS.includes(view)) view = "home";
  for (const v of VIEWS) $("#v-" + v).hidden = v !== view;
  ensureInit(view);
  hooks[view]?.enter?.();
  window.scrollTo(0, 0);
}

export function go(view) {
  if (location.hash !== "#" + view) location.hash = view;
  else show(view);
}

export function startRouter() {
  window.addEventListener("hashchange", () => show(location.hash.slice(1)));
  document.addEventListener("click", (e) => {
    const b = e.target.closest("[data-go]");
    if (b) { e.preventDefault(); go(b.dataset.go); }
  });
  show(location.hash.slice(1) || "home");
}
