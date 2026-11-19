"""Renderiza resultados reales de k6; nunca sustituye los umbrales originales."""
import argparse
import hashlib
import html
import json
import subprocess
import sys
import threading
import webbrowser
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--report-dir", type=Path, default=Path(__file__).resolve().parent.parent / "reports",
                    help="Carpeta con los JSON de ejecución de k6.")
parser.add_argument("--servir", action="store_true", help="Visualizar los informes en un servidor local.")
parser.add_argument("--puerto", type=int, default=8766, help="Puerto local (por defecto: 8766).")
parser.add_argument("--sin-abrir", action="store_true", help="No abrir el navegador automáticamente.")
args = parser.parse_args()
if not 1 <= args.puerto <= 65535:
    parser.error("--puerto debe estar entre 1 y 65535")
ROOT = args.report_dir.resolve()
OUT = ROOT / "evidencias"
OUT.mkdir(parents=True, exist_ok=True)
escape = lambda value: html.escape(str(value))
CSS = """
body{margin:0;background:#edf2f7;color:#17283d;font:16px/1.5 Segoe UI,Arial,sans-serif}
main{max-width:1000px;margin:32px auto;padding:32px;background:white;border-radius:16px}
h1{font-size:30px;margin:8px 0}h2{font-size:20px;margin-top:28px}
.eyebrow{color:#52708b;font-size:13px;letter-spacing:2px;font-weight:700}
.badge{display:inline-block;background:#dcfce7;color:#166534;padding:6px 16px;border-radius:20px;font-weight:700}
.fail{background:#fee2e2;color:#991b1b}
.meta{color:#526477;font-size:14px}.cards{display:flex;gap:14px;margin:24px 0}
.card{background:#f3f7fb;padding:18px;border-radius:10px;flex:1}.value{font-size:25px;font-weight:700}
table{border-collapse:collapse;width:100%;font-size:14px}th,td{text-align:left;padding:12px;border-bottom:1px solid #dde5ed}
th{background:#edf3f9}code{font-size:12px;overflow-wrap:anywhere}
.note{background:#f0f6ff;border-left:4px solid #4384c7;padding:14px;font-size:14px}
a{color:#225fa6}.hash{font-size:11px;word-break:break-all;color:#60758a}
"""


def document(title, body):
    return f'<!doctype html><html lang="es"><meta charset="utf-8"><title>{escape(title)}</title><style>{CSS}</style><main>{body}</main></html>'


names = {
    "portal-load": ("Portal · carga", "Portal y assets de React, sin API. Escalones originales del escenario."),
    "auth-load": ("Autenticación · carga", "GET /api/me y JWKS directamente contra ms-auth. Hasta 100 usuarios."),
    "ai-load": ("Asistente local · carga", "Salud y chat contra ms-ai. Proveedor local, sin llamadas a un LLM externo."),
    "smoke": ("Humo", "Recorrido mínimo y revisión de cartera CSV/XLSX; no es una prueba de capacidad."),
    "load": ("Carga", "Carga de usuarios concurrentes por el camino completo, con los escalones originales."),
    "stress": ("Estrés", "Ritmo creciente hasta el límite original; un aborto por latencia no se presenta como aprobación."),
    "spike": ("Pico", "Subida súbita a 150 usuarios y comprobación de la fase posterior."),
    "endurance": ("Resistencia", "Carga sostenida durante 10 minutos; sin reducir la duración original."),
    "volume": ("Volumen", "Revisión de carteras ficticias CSV y XLSX; rollback de las cargas."),
    "concurrency": ("Concurrencia", "Consultas concurrentes. Repactaciones desactivadas: no se validan las escrituras."),
    "scalability": ("Escalabilidad", "Escalones originales de 10, 25 y 50 usuarios."),
    "response-time": ("Tiempo de respuesta", "Diez recorridos con un solo usuario, sin ruido de concurrencia."),
    "api-performance": ("API", "Ritmo constante contra el gateway, con umbral original de throughput."),
    "database-performance": ("Base de datos", "Lecturas y agregaciones a través de ms-debt; no es una prueba SQL directa."),
    "recovery": ("Recuperación", "Sobrecarga, pausa y carga normal; se conservan los criterios originales."),
    "capacity": ("Capacidad", "Escalones originales de 50, 100, 200 y 400 peticiones por segundo."),
}
runs_path = ROOT / "ejecuciones.json"
runs = json.loads(runs_path.read_text(encoding="utf-8-sig")) if runs_path.exists() else []
if isinstance(runs, dict):
    runs = [runs]
suite_path = ROOT / "suite-completa-ejecuciones.json"
suite = json.loads(suite_path.read_text(encoding="utf-8-sig")) if suite_path.exists() else None
if suite:
    runs.extend(suite.get("ejecuciones", []))
index_rows = []
manifest = []
for run in runs:
    name = run["prueba"]
    filename = run.get("archivo") if "archivo" in run else f"{name}.json"
    path = ROOT / filename if filename else None
    data = json.loads(path.read_text()) if path and path.exists() else {}
    metrics = data.get("metrics", {})
    thresholds = [
        (metric, rule, result.get("ok") is True)
        for metric, values in metrics.items()
        for rule, result in values.get("thresholds", {}).items()
    ]
    count = metrics.get("http_reqs", {}).get("values", {}).get("count", 0)
    checks = metrics.get("checks", {}).get("values", {})
    approved = run["codigo"] == 0 and bool(thresholds) and all(t[2] for t in thresholds) and count > 0 and checks.get("fails", 0) == 0
    status = "APROBADA" if approved else "NO APROBADA"
    title, description = names.get(name, (name, "Escenario de rendimiento de k6."))
    duration = data.get("state", {}).get("testRunDurationMs", 0) / 1000
    errors = metrics.get("http_req_failed", {}).get("values", {}).get("rate", 0) * 100
    rows = ""
    for metric, rule, ok in thresholds:
        values = metrics[metric].get("values", {})
        if "p(95)" in rule and "p(95)" in values:
            observed = f'{values["p(95)"]:.2f} ms'
        elif "count" in rule:
            observed = f'{values.get("count", 0):,.0f}'
        elif metrics[metric].get("type") == "counter":
            observed = f'{values.get("rate", 0):.2f}/s'
        else:
            observed = f'{values.get("rate", 0) * 100:.2f} %'
        rows += f'<tr><td><code>{escape(metric)}</code></td><td>{escape(rule)}</td><td>{observed}</td><td>{"✓ OK" if ok else "✗ FALLA"}</td></tr>'
    digest = hashlib.sha256(path.read_bytes()).hexdigest() if path and path.exists() else "Sin reporte"
    logname = f"suite-{name}.log" if "archivo" in run else f"{name}.log"
    runfile = "suite-completa-ejecuciones.json" if "archivo" in run else "ejecuciones.json"
    rawlink = f'<a href="../{escape(filename)}">JSON</a> · ' if filename else ""
    scope = "IA externa, chat de la suite y verificaciones de pasarelas desactivados. Repactaciones desactivadas." if "archivo" in run else "Escenario original por servicio."
    body = f"""
    <div class="eyebrow">DATABRIDGE / EVIDENCIA DE RENDIMIENTO / K6</div>
    <h1>{escape(title)}</h1>
    <span class="badge {'' if approved else 'fail'}">{status}</span>
    <p>{escape(description)}</p>
    <div class="meta">Inicio UTC: {escape(run['inicio'])}<br>Fin UTC: {escape(run['fin'])}<br>
    Entorno: {escape(run['entorno'])} · Código de salida: {run['codigo']}</div>
    <div class="cards">
      <div class="card">Peticiones HTTP<div class="value">{count:,.0f}</div></div>
      <div class="card">Errores HTTP<div class="value">{errors:.2f} %</div></div>
      <div class="card">Duración<div class="value">{duration:.1f} s</div></div>
    </div>
    <h2>Criterios de aceptación originales</h2>
    <table><thead><tr><th>Métrica</th><th>Umbral</th><th>Observado</th><th>Resultado</th></tr></thead><tbody>{rows}</tbody></table>
    <p>Checks: {checks.get('passes', 0):,.0f} correctos · {checks.get('fails', 0):,.0f} fallidos.</p>
    <p class="meta">{scope}</p>
    <div class="note">Informe HTML generado a partir del JSON real de k6. No es la interfaz nativa de k6.
    No se cambiaron los umbrales ni la carga del escenario. Esta ejecución no valida la suite completa ni el código del directorio contra un despliegue reconstruido.</div>
    <p class="meta">Fuentes: {rawlink}<a href="../{logname}">Log</a> · <a href="../{runfile}">Ejecución</a></p>
    <div class="hash">SHA-256 del JSON: {digest}</div>
    """
    (OUT / f"{name}.html").write_text(document(title, body), encoding="utf-8")
    index_rows.append(f'<tr><td><a href="{name}.html">{escape(title)}</a></td><td>{status}</td><td>{count:,.0f}</td><td>{duration:.1f} s</td></tr>')
    manifest.append({"prueba": name, "aprobada": approved, "sha256": digest})

suite_count = len(suite.get("ejecuciones", [])) if suite else 0
restored = "Sí" if suite and suite.get("limitesRestaurados") else "Pendiente / no verificado"
scope_note = (
    f"Suite modular: {suite_count}/13 escenarios ejecutados. Límites originales restaurados: {restored}. "
    "Los límites se subieron temporalmente con autorización; se conservaron carga y umbrales originales. "
    "Chat de la suite, repactaciones y verificaciones de pasarelas desactivados. "
    if suite else
    "La suite modular de 12 pruebas y humo no se ejecutó: el gateway mantiene su límite de 120 peticiones/minuto. "
)
body = f"""
<div class="eyebrow">DATABRIDGE / RESULTADOS REALES / K6</div>
<h1>Pruebas de rendimiento</h1>
<p>Entornos medidos: {escape(', '.join(sorted({run.get('entorno', 'No especificado') for run in runs})) or 'Sin ejecuciones registradas')}.</p>
<table><tr><th>Prueba</th><th>Estado</th><th>Peticiones</th><th>Duración</th></tr>{''.join(index_rows)}</table>
<h2>Alcance</h2>
<div class="note">{scope_note}
No se abrieron cobros ni se repactaron deudas. La autenticación genera códigos y correos en el buzón de prueba.
Estos resultados corresponden al despliegue activo, no a una reconstrucción del directorio revisado.</div>
<p class="meta">Cada informe incluye métricas, umbrales y enlaces a las evidencias originales.</p>
<p class="meta">En el visor local, recarga esta página para incorporar las pruebas que vayan terminando.</p>
"""
(OUT / "index.html").write_text(document("DataBridge · Rendimiento", body), encoding="utf-8")
(OUT / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
print(json.dumps(manifest, indent=2))

if args.servir:
    generation_lock = threading.Lock()

    class LocalReportsHandler(SimpleHTTPRequestHandler):
        """Solo publica los informes locales; no ejecuta nuevas pruebas."""

        def do_GET(self):
            requested = urlsplit(self.path).path
            if requested == "/":
                self.send_response(302)
                self.send_header("Location", "/evidencias/index.html")
                self.end_headers()
                return
            local_path = Path(self.translate_path(requested)).resolve()
            if (not local_path.is_relative_to(ROOT)
                    or local_path.suffix.lower() not in {".html", ".json", ".log", ".md", ".txt"}
                    or not local_path.is_file()):
                self.send_error(404)
                return
            if requested.startswith("/evidencias/") and local_path.suffix == ".html":
                with generation_lock:
                    result = subprocess.run(
                        [sys.executable, str(Path(__file__).resolve()), "--report-dir", str(ROOT)],
                        capture_output=True, text=True,
                    )
                    if result.returncode:
                        print("No se pudo actualizar; se conserva el último informe válido.", flush=True)
            super().do_GET()

        def do_HEAD(self):
            requested = urlsplit(self.path).path
            local_path = Path(self.translate_path(requested)).resolve()
            if (not local_path.is_relative_to(ROOT) or not local_path.is_file()
                    or local_path.suffix.lower() not in {".html", ".json", ".log", ".md", ".txt"}):
                self.send_error(404)
                return
            super().do_HEAD()

        def end_headers(self):
            self.send_header("Cache-Control", "no-store")
            super().end_headers()

    try:
        server = ThreadingHTTPServer(("127.0.0.1", args.puerto), partial(LocalReportsHandler, directory=str(ROOT)))
    except OSError as error:
        parser.exit(1, f"No se pudo abrir el puerto {args.puerto}: {error}. Usa --puerto con otro valor.\n")
    url = f"http://127.0.0.1:{server.server_port}/evidencias/index.html"
    print(f"\nVisor local: {url}\nRecarga para actualizar. Ctrl+C para detener el servidor.", flush=True)
    if not args.sin_abrir:
        webbrowser.open(url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
