"use strict";

// Builds the type-specific "Answer settings" inputs and serializes them into the hidden
// config_json field on submit. The server validates the result (app/question_types.py).
document.addEventListener("DOMContentLoaded", () => {
  const form = document.getElementById("question-form");
  if (!form) return;

  const initial = JSON.parse(document.getElementById("question-data").textContent);
  const typeSelect = form.querySelector("select[name=type]");
  const editor = document.getElementById("config-editor");
  const output = form.querySelector("input[name=config_json]");

  const emptyOption = () => ({ key: "", label: "" });
  const emptyField = () => ({ key: "", label: "", unit: "", min: null, max: null, decimals: 0 });

  const DEFAULTS = {
    scale: () => ({ min: 1, max: 10, step: 1, min_label: "", max_label: "" }),
    boolean: () => ({ true_label: "Yes", false_label: "No" }),
    text: () => ({ multiline: false, max_length: 1000 }),
    time: () => ({}),
    numeric: () => ({ fields: [emptyField()] }),
    single_select: () => ({ options: [emptyOption(), emptyOption()] }),
    multi_select: () => ({ options: [emptyOption(), emptyOption()], min: 0, max: null }),
  };

  // --- small DOM helpers ------------------------------------------------------

  function el(tag, attrs = {}, ...children) {
    const node = document.createElement(tag);
    for (const [name, value] of Object.entries(attrs)) {
      if (value === null || value === undefined || value === false) continue;
      if (name === "class") node.className = value;
      else if (name.startsWith("data-")) node.setAttribute(name, value);
      else if (name in node) node[name] = value;
      else node.setAttribute(name, value);
    }
    node.append(...children.filter((c) => c !== null && c !== undefined));
    return node;
  }

  function input(name, value, attrs = {}) {
    return el("input", { "data-name": name, value: value ?? "", ...attrs });
  }

  function field(labelText, control, hint) {
    return el("label", {}, labelText, control, hint ? el("span", { class: "hint" }, hint) : null);
  }

  function slug(text) {
    let s = text.toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "");
    if (/^[0-9]/.test(s)) s = "n" + s;
    return s.slice(0, 32);
  }

  // Read values from inputs directly inside scope (not inside nested rows).
  function read(scope, name) {
    const node = scope.querySelector(`[data-name="${name}"]`);
    if (!node) return undefined;
    if (node.type === "checkbox") return node.checked;
    return node.value.trim();
  }

  function number(raw) {
    if (raw === "" || raw === undefined) return null;
    const n = Number(raw);
    return Number.isNaN(n) ? raw : n; // let the server report non-numbers
  }

  function text(raw) {
    return raw === "" || raw === undefined ? null : raw;
  }

  // Drop nulls so the server applies its defaults (and reports required fields clearly).
  function compact(obj) {
    return Object.fromEntries(Object.entries(obj).filter(([, v]) => v !== null));
  }

  // --- repeatable rows (options, numeric fields) -------------------------------

  function rowsEditor(items, columns, makeEmpty, colsClass, addLabel) {
    const head = el("div", { class: `rows-head ${colsClass}` }, ...columns.map((c) => el("span", {}, c.title)), el("span"));
    const list = el("div", { class: "rows" });
    const add = el("button", { type: "button", class: "secondary small" }, addLabel);

    function addRow(item) {
      const row = el("div", { class: `row ${colsClass}` });
      for (const col of columns) {
        // Each cell carries its own label, shown only on narrow screens where the header row is hidden.
        row.append(el("label", { class: "cell" },
          el("span", { class: "cell-label" }, col.title),
          input(col.name, item[col.name], { placeholder: col.placeholder || "", ...(col.attrs || {}) })));
      }
      const remove = el("button", { type: "button", class: "icon", title: "Remove", "aria-label": "Remove" }, "×");
      remove.addEventListener("click", () => row.remove());
      row.append(remove);

      // Fill the key from the label until the key is edited by hand. Existing keys are never
      // changed automatically: answers store the key, so it should stay stable across versions.
      const key = row.querySelector('[data-name="key"]');
      const label = row.querySelector('[data-name="label"]');
      if (key && label) {
        let auto = key.value === "";
        key.addEventListener("input", () => { auto = key.value === ""; });
        label.addEventListener("input", () => { if (auto) key.value = slug(label.value); });
      }
      list.append(row);
    }

    items.forEach(addRow);
    add.addEventListener("click", () => addRow(makeEmpty()));
    return { node: el("div", {}, head, list, add), rows: () => [...list.children] };
  }

  const OPTION_COLUMNS = [
    { name: "label", title: "Label", placeholder: "Shown to you" },
    { name: "key", title: "Key", placeholder: "stored_value", attrs: { pattern: "[a-z][a-z0-9_]{0,31}" } },
  ];
  const KEY_HINT = "Keys are what's stored with each answer. Keep them unchanged when editing so answers stay comparable.";

  // --- per-type editors: render(config) -> { node, collect() } -----------------

  const EDITORS = {
    scale(c) {
      const node = el("div", { class: "grid" },
        field("Lowest value", input("min", c.min, { type: "number", step: 1, required: true })),
        field("Highest value", input("max", c.max, { type: "number", step: 1, required: true })),
        field("Step", input("step", c.step ?? 1, { type: "number", step: 1, min: 1 })),
        field("Label for lowest", input("min_label", c.min_label, { placeholder: "e.g. Wide awake" })),
        field("Label for highest", input("max_label", c.max_label, { placeholder: "e.g. Exhausted" })),
      );
      return {
        node,
        collect: () => compact({
          min: number(read(node, "min")), max: number(read(node, "max")), step: number(read(node, "step")),
          min_label: text(read(node, "min_label")), max_label: text(read(node, "max_label")),
        }),
      };
    },

    boolean(c) {
      const node = el("div", { class: "grid" },
        field("Label for yes", input("true_label", c.true_label ?? "Yes", { required: true })),
        field("Label for no", input("false_label", c.false_label ?? "No", { required: true })),
      );
      return { node, collect: () => compact({ true_label: text(read(node, "true_label")), false_label: text(read(node, "false_label")) }) };
    },

    text(c) {
      const node = el("div", { class: "grid" },
        field("Maximum length (characters)", input("max_length", c.max_length ?? 1000, { type: "number", min: 1, max: 10000 })),
        el("label", { class: "check" }, input("multiline", null, { type: "checkbox", checked: !!c.multiline }), "Allow several lines"),
      );
      return { node, collect: () => compact({ max_length: number(read(node, "max_length")), multiline: read(node, "multiline") }) };
    },

    time() {
      return {
        node: el("p", { class: "muted" }, "No settings. The answer is a 24-hour clock time such as 23:15, with no date."),
        collect: () => ({}),
      };
    },

    numeric(c) {
      const rows = rowsEditor(
        c.fields && c.fields.length ? c.fields : [emptyField()],
        [
          { name: "label", title: "Label", placeholder: "Systolic" },
          { name: "key", title: "Key", placeholder: "systolic", attrs: { pattern: "[a-z][a-z0-9_]{0,31}" } },
          { name: "unit", title: "Unit", placeholder: "mmHg" },
          { name: "min", title: "Min", attrs: { type: "number", step: "any" } },
          { name: "max", title: "Max", attrs: { type: "number", step: "any" } },
          { name: "decimals", title: "Decimals", attrs: { type: "number", min: 0, max: 6, step: 1 } },
        ],
        emptyField, "cols-numeric", "+ Add field",
      );
      return {
        node: el("div", {}, el("p", { class: "hint" }, "One input per field. " + KEY_HINT), rows.node),
        collect: () => ({
          fields: rows.rows().map((r) => compact({
            key: read(r, "key"), label: read(r, "label"), unit: text(read(r, "unit")),
            min: number(read(r, "min")), max: number(read(r, "max")), decimals: number(read(r, "decimals")),
          })),
        }),
      };
    },

    single_select(c) {
      const rows = rowsEditor(c.options && c.options.length ? c.options : [emptyOption(), emptyOption()],
        OPTION_COLUMNS, emptyOption, "cols-options", "+ Add option");
      return {
        node: el("div", {}, el("p", { class: "hint" }, KEY_HINT), rows.node),
        collect: () => ({ options: rows.rows().map((r) => ({ key: read(r, "key"), label: read(r, "label") })) }),
      };
    },

    multi_select(c) {
      const rows = rowsEditor(c.options && c.options.length ? c.options : [emptyOption(), emptyOption()],
        OPTION_COLUMNS, emptyOption, "cols-options", "+ Add option");
      const limits = el("div", { class: "grid" },
        field("Minimum selected", input("min", c.min ?? 0, { type: "number", min: 0 })),
        field("Maximum selected", input("max", c.max, { type: "number", min: 1, placeholder: "No limit" })),
      );
      return {
        node: el("div", { class: "stack" }, el("p", { class: "hint" }, KEY_HINT), rows.node, limits),
        collect: () => compact({
          options: rows.rows().map((r) => ({ key: read(r, "key"), label: read(r, "label") })),
          min: number(read(limits, "min")), max: number(read(limits, "max")),
        }),
      };
    },
  };

  let current = null;

  function show(type, config) {
    current = EDITORS[type](config || DEFAULTS[type]());
    editor.replaceChildren(current.node);
  }

  show(initial.type, initial.config);

  if (typeSelect && !typeSelect.disabled) {
    typeSelect.addEventListener("change", () => show(typeSelect.value, null));
  }

  form.addEventListener("submit", () => {
    output.value = JSON.stringify(current.collect());
  });
});
