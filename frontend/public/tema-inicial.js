// Síncrono en head: decide el tema antes de pintar, sin script inline.
(function () {
  var guardado = null;
  try { guardado = localStorage.getItem("tb-tema"); } catch (e) {}
  var oscuro = guardado ? guardado === "dark"
    : !!(window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches);
  document.documentElement.setAttribute("data-theme", oscuro ? "dark" : "light");
})();
