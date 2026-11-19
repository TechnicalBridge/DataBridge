"""Pruebas locales del generador; no necesitan Docker ni llaman a la aplicación."""
import json
import os
import queue
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path

GENERATOR = Path(__file__).with_name("generar-evidencias.py")


class EvidenciasTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.run = {
            "prueba": "load", "codigo": 0, "archivo": "load.json",
            "inicio": "2026-11-14T00:00:00Z", "fin": "2026-11-14T00:02:00Z",
            "entorno": "prueba-local",
        }
        self.data = {"metrics": {
            "http_reqs": {"type": "counter", "values": {"count": 100, "rate": 50}},
            "http_req_failed": {"type": "rate", "values": {"rate": 0},
                                "thresholds": {"rate<0.01": {"ok": True}}},
            "checks": {"type": "rate", "values": {"passes": 100, "fails": 0}},
        }, "state": {"testRunDurationMs": 120000}}

    def write_inputs(self):
        (self.root / "suite-completa-ejecuciones.json").write_text(json.dumps({
            "limitesRestaurados": True, "ejecuciones": [self.run],
        }), encoding="utf-8")
        (self.root / "load.json").write_text(json.dumps(self.data), encoding="utf-8")

    def generate(self):
        self.write_inputs()
        subprocess.run([sys.executable, str(GENERATOR), "--report-dir", str(self.root)],
                       check=True, capture_output=True)
        return json.loads((self.root / "evidencias" / "manifest.json").read_text())[0]

    def test_suite_without_legacy_manifest(self):
        self.assertTrue(self.generate()["aprobada"])
        self.assertIn("prueba-local", (self.root / "evidencias" / "index.html").read_text(encoding="utf-8"))

    def test_failed_threshold(self):
        self.data["metrics"]["http_req_failed"]["thresholds"]["rate<0.01"]["ok"] = False
        self.assertFalse(self.generate()["aprobada"])

    def test_nonzero_exit_code(self):
        self.run["codigo"] = 99
        self.assertFalse(self.generate()["aprobada"])

    def test_failed_check(self):
        self.data["metrics"]["checks"]["values"]["fails"] = 1
        self.assertFalse(self.generate()["aprobada"])

    def test_unknown_scenario(self):
        self.run["prueba"] = "escenario-nuevo"
        self.assertTrue(self.generate()["aprobada"])

    def test_local_server_refresh_and_restricted_files(self):
        self.write_inputs()
        # Puerto efímero solo para la prueba, sin conflicto con los visores abiertos.
        # El CLI público valida puertos 1..65535; se reserva uno libre para el test.
        import socket
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            port = reservation.getsockname()[1]
        env = dict(os.environ, PYTHONIOENCODING="utf-8")
        process = subprocess.Popen(
            [sys.executable, str(GENERATOR), "--report-dir", str(self.root),
             "--servir", "--sin-abrir", "--puerto", str(port)],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", env=env,
        )
        def cleanup():
            process.terminate()
            process.wait(timeout=10)
            process.stdout.close()
        self.addCleanup(cleanup)
        ready = queue.Queue()
        def output():
            for line in process.stdout:
                if line.startswith("Visor local:"):
                    ready.put(line)
                    return
        threading.Thread(target=output, daemon=True).start()
        ready.get(timeout=15)
        base = f"http://127.0.0.1:{port}"
        with urllib.request.urlopen(base + "/") as response:
            self.assertEqual(response.status, 200)
            self.assertEqual(response.headers["Cache-Control"], "no-store")
            self.assertIn("APROBADA", response.read().decode("utf-8"))
        self.run["codigo"] = 99
        self.write_inputs()
        with urllib.request.urlopen(base + "/evidencias/load.html") as response:
            self.assertIn("NO APROBADA", response.read().decode("utf-8"))
        (self.root / "privado.py").write_text("no publicar", encoding="utf-8")
        for url in ("/privado.py", "/evidencias/"):
            with self.assertRaises(urllib.error.HTTPError) as error:
                urllib.request.urlopen(base + url)
            self.assertEqual(error.exception.code, 404)


if __name__ == "__main__":
    unittest.main()
