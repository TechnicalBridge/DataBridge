import os
from pathlib import Path
from datetime import datetime
import bcchapi

def cargar_env():
    candidatos = [
        Path(__file__).resolve().parent / ".env",
        Path.cwd() / ".env",
        Path("c:/Users/kirto/OneDrive/Escritorio/TB_web-main/.env"),
    ]
    for ruta in candidatos:
        if ruta.exists():
            with open(ruta, "r", encoding="utf-8") as f:
                for linea in f:
                    linea = linea.strip()
                    if linea and not linea.startswith("#") and "=" in linea:
                        k, v = linea.split("=", 1)
                        k = k.strip()
                        v = v.strip().strip('"').strip("'")
                        os.environ.setdefault(k, v)
            if os.getenv("BCENTRAL_USER") and os.getenv("BCENTRAL_PASS"):
                break

cargar_env()

user = os.getenv("BCENTRAL_USER")
password = os.getenv("BCENTRAL_PASS")

if not user or not password:
    raise ValueError("Faltan las variables BCENTRAL_USER o BCENTRAL_PASS en el archivo .env")

siete = bcchapi.Siete(user, password)
fec = datetime.now().strftime("%Y-%m-%d")

buscar = siete.cuadro(
    series=["F073.UFF.PRE.Z.D"],
    nombres=["Unidad de Fomento (UF)"],
    desde=fec
)

print(buscar)
