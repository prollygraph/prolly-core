/*
 * Copyright 2026 Earasoft
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/* Bind — a no-build declarative binding microlibrary (plan playground-declarative-binding).
 *
 * The whole idea: dynamic HTML lives as <template>s with data-* attributes IN THE HTML
 * FILE; JavaScript assigns plain DATA to a view-model and the bound subtree re-renders.
 * Templates are LOGIC-FREE by design (D-2): a binding names a dotted path plus at most
 * one registered formatter ("path|fmt") — no expressions, no ternaries, no comparisons.
 * Anything conditional or computed is precomputed in a JavaScript view-model builder,
 * where it is testable. (Alpine.js moves JavaScript INTO the HTML; this moves only data.)
 *
 * Directives:
 *   data-text="path|fmt?"     -> el.textContent (NEVER innerHTML — D-4: no injection path)
 *   data-show="path"          -> el.hidden = !value
 *   data-each="item of path"  -> stamp the <template> child once per array item; stamped
 *                                nodes FOLLOW the template; nestable, scopes chain and
 *                                shadow ("item" resolves innermost-first)
 *   data-attr-NAME="path"     -> setAttribute(NAME, v); null/false REMOVES the attribute
 *                                (data-attr-data-arm -> the data-arm attribute, etc.)
 *   data-class-NAME="path"    -> classList.toggle(NAME, !!v)
 *
 * Rendering model (D-3): a walking re-evaluator — any assignment to the returned
 * view-model schedules ONE microtask that re-walks the mounted subtree. No virtual DOM,
 * no dependency graph: at playground scale the dumb pass is unmeasurable and the
 * simplicity is the point. Reactivity is SHALLOW: replace, don't mutate
 * (vm.rows = [...vm.rows, r] — a push() is invisible). Bound regions are re-stamped
 * wholesale, so they must be read-only output, not hosts of input state.
 *
 * Errors are LOUD: an unknown formatter or malformed data-each throws — a binding that
 * silently renders nothing is the failure mode this page never accepts.
 */
'use strict';

const Bind = (() => {
  const formatters = Object.create(null);

  /** Register a named formatter usable as "path|name" in any binding. */
  function format(name, fn) { formatters[name] = fn; }

  /** Resolve a dotted path against the scope chain, innermost scope first. */
  function resolve(path, scopes) {
    const parts = path.split('.');
    for (let i = scopes.length - 1; i >= 0; i--) {
      if (!(parts[0] in scopes[i])) continue;
      let v = scopes[i];
      for (const p of parts) {
        if (v == null) return undefined;
        v = v[p];
      }
      return v;
    }
    return undefined;
  }

  /** Evaluate a "path|fmt?" spec. Unknown formatters throw — never render silently wrong. */
  function evalSpec(spec, scopes) {
    const bar = spec.indexOf('|');
    const path = (bar < 0 ? spec : spec.slice(0, bar)).trim();
    const v = resolve(path, scopes);
    if (bar < 0) return v;
    const name = spec.slice(bar + 1).trim();
    const f = formatters[name];
    if (!f) throw new Error('Bind: unknown formatter "' + name + '" in "' + spec + '"');
    return f(v);
  }

  const dashify = (camel) => camel.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase());

  function applyDirectives(el, scopes) {
    const d = el.dataset;
    if (d.text !== undefined) {
      const v = evalSpec(d.text, scopes);
      el.textContent = v == null ? '' : String(v); // textContent, never innerHTML (D-4)
    }
    if (d.show !== undefined) el.hidden = !evalSpec(d.show, scopes);
    for (const key in d) {
      if (key.startsWith('attr') && key.length > 4) { // attrTitle -> title; attrDataArm -> data-arm
        const name = dashify(key.slice(4)).replace(/^-/, '');
        const v = evalSpec(d[key], scopes);
        if (v == null || v === false) el.removeAttribute(name);
        else el.setAttribute(name, v === true ? '' : String(v));
      } else if (key.startsWith('class') && key.length > 5) { // classClipped -> clipped
        const name = dashify(key.slice(5)).replace(/^-/, '');
        el.classList.toggle(name, !!evalSpec(d[key], scopes));
      }
    }
  }

  function renderEl(el, scopes) {
    const eachSpec = el.dataset ? el.dataset.each : undefined;
    if (eachSpec !== undefined) {
      const m = /^([A-Za-z_$][\w$]*)\s+of\s+(.+)$/.exec(eachSpec);
      if (!m) throw new Error('Bind: malformed data-each "' + eachSpec + '" (want "item of path")');
      const tpl = el.querySelector(':scope > template');
      if (!tpl) throw new Error('Bind: data-each needs a <template> child');
      applyDirectives(el, scopes); // the container may bind attrs of its own
      // static children BEFORE the template (a panel title, say) render with the
      // OUTER scope; everything after the template is the stamp region
      for (const child of [...el.children]) {
        if (child.tagName === 'TEMPLATE') break;
        renderEl(child, scopes);
      }
      // stamped nodes live AFTER the template; clear the previous stamp, re-stamp
      while (tpl.nextSibling) tpl.nextSibling.remove();
      const items = resolve(m[2], scopes);
      for (const item of items || []) {
        const frag = tpl.content.cloneNode(true);
        const kids = [...frag.children];
        el.appendChild(frag);
        for (const kid of kids) renderEl(kid, scopes.concat({ [m[1]]: item }));
      }
      return; // children were handled by the stamp walk
    }
    applyDirectives(el, scopes);
    for (const child of [...el.children]) {
      if (child.tagName === 'TEMPLATE') continue;
      renderEl(child, scopes);
    }
  }

  /**
   * Bind a DOM subtree to a data object. Returns the reactive view-model: assigning any
   * property re-renders the subtree (assignments in one tick coalesce to one render).
   */
  function mount(root, data) {
    let scheduled = false;
    const proxy = new Proxy(data, {
      set(target, key, value) {
        target[key] = value;
        if (!scheduled) {
          scheduled = true;
          queueMicrotask(() => { scheduled = false; renderEl(root, [proxy]); });
        }
        return true;
      },
    });
    renderEl(root, [proxy]);
    return proxy;
  }

  return { mount, format };
})();
