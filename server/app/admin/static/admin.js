"use strict";

// Confirmation prompt for forms marked with data-confirm (inline handlers are blocked by the CSP).
document.addEventListener("submit", (event) => {
  const message = event.target.dataset && event.target.dataset.confirm;
  if (message && !window.confirm(message)) event.preventDefault();
});

// Check-in editor: add, reorder and remove questions. Order of the hidden inputs is the asking order.
document.addEventListener("DOMContentLoaded", () => {
  const picker = document.getElementById("checkin-questions");
  if (!picker) return;
  const list = picker.querySelector("ol");
  const select = picker.querySelector("select");
  const addButton = picker.querySelector("[data-action=add]");
  const template = document.getElementById("checkin-item");

  list.addEventListener("click", (event) => {
    const button = event.target.closest("button[data-move]");
    if (!button) return;
    const item = button.closest("li");
    const move = button.dataset.move;
    if (move === "up" && item.previousElementSibling) item.previousElementSibling.before(item);
    else if (move === "down" && item.nextElementSibling) item.nextElementSibling.after(item);
    else if (move === "remove") item.remove();
  });

  if (addButton) {
    addButton.addEventListener("click", () => {
      const option = select.selectedOptions[0];
      if (!option || !option.value) return;
      const already = [...list.querySelectorAll("input[name=question_ids]")].some((i) => i.value === option.value);
      if (!already) {
        const item = template.content.firstElementChild.cloneNode(true);
        item.querySelector("input").value = option.value;
        item.querySelector(".grow").textContent = option.textContent;
        list.append(item);
      }
      select.value = "";
    });
  }
});
