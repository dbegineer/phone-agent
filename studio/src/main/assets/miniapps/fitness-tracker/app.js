(function () {
  "use strict";
  var KEY = "workouts";
  var GOAL = 150; // weekly minutes goal

  function load() {
    try { return JSON.parse(Host.storageGet(KEY) || "[]"); } catch (e) { return []; }
  }
  function save(items) { return Host.storageSet(KEY, JSON.stringify(items)); }
  function dayKey(d) {
    return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0");
  }
  function el(id) { return document.getElementById(id); }

  function render() {
    var items = load();
    var days = [], i;
    for (i = 6; i >= 0; i--) {
      var d = new Date(); d.setDate(d.getDate() - i);
      days.push({ key: dayKey(d), label: "SMTWTFS".charAt(d.getDay()), min: 0 });
    }
    items.forEach(function (w) {
      days.forEach(function (day) { if (day.key === w.date) day.min += w.minutes; });
    });
    var max = Math.max.apply(null, days.map(function (x) { return x.min; }).concat([30]));
    var chart = el("chart"); chart.textContent = "";
    var total = 0;
    days.forEach(function (day) {
      total += day.min;
      var col = document.createElement("div"); col.className = "col";
      var bar = document.createElement("div"); bar.className = "bar";
      bar.style.height = Math.round((day.min / max) * 100) + "px";
      var lab = document.createElement("span"); lab.textContent = day.label + (day.min ? " " + day.min : "");
      col.appendChild(bar); col.appendChild(lab); chart.appendChild(col);
    });
    el("total").textContent = "Total " + total + " min of " + GOAL + " weekly goal";

    var list = el("list"); list.textContent = "";
    items.slice().reverse().slice(0, 10).forEach(function (w) {
      var li = document.createElement("li");
      var t = document.createElement("span");
      t.textContent = w.date + "  " + w.type + "  " + w.minutes + " min";
      var b = document.createElement("button"); b.type = "button"; b.textContent = "Delete";
      b.addEventListener("click", function () {
        save(load().filter(function (x) { return x.id !== w.id; })); render();
      });
      li.appendChild(t); li.appendChild(b); list.appendChild(li);
    });
    return total;
  }

  el("add").addEventListener("click", function () {
    var minutes = parseInt(el("minutes").value, 10);
    var msg = el("msg");
    if (!(minutes >= 1 && minutes <= 600)) { msg.textContent = "Enter minutes between 1 and 600."; return; }
    var before = render.last || 0;
    var items = load();
    items.push({ id: Date.now(), date: dayKey(new Date()), type: el("type").value, minutes: minutes });
    if (!save(items)) { msg.textContent = "Storage full."; return; }
    el("minutes").value = "";
    var total = render();
    msg.textContent = "Saved.";
    if (before < GOAL && total >= GOAL) Host.notify("Goal reached", "You hit " + GOAL + " minutes this week.");
    render.last = total;
  });

  render.last = render();
})();
