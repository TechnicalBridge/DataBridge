// =============================================================================
//  5. Volume testing: carteras grandes en CSV y Excel.
// =============================================================================
//  Sube la cartera ficticia de data/cartera-volumen.csv y su gemela .xlsx a
//  POST /api/debts/cartera/revision. Esa ruta pasa por la misma ingesta que
//  una carga real, con las mismas validaciones, pero siempre hace rollback:
//  no deja deudas, lotes, contactos ni correos.
//
//  Los archivos tienen 400 deudores y 800 filas de deuda cada uno. Se generan
//  con scripts/generar-datos.py. Si se cambia el tamano, se regenera y se
//  vuelve a correr; no hay ningun dato real.
//
//      python scripts/generar-datos.py
//      docker compose --profile carga run --rm k6 run /performance-tests/k6/scenarios/volume.js
// =============================================================================
import { fail } from 'k6';
import { DATA_DIR, exigirEntornoDePruebas } from '../config/env.js';
import { opciones } from '../config/scenarios.js';
import { sesionEmpresaObligatoria } from '../modules/auth.js';
import { campanaDisponible, revisar, TIPO_CSV, TIPO_XLSX } from '../modules/cartera.js';
import { esRevisionCartera } from '../utils/checks.js';
import { medir } from '../utils/http.js';
import { volumen } from '../utils/metrics.js';
import { resumen } from '../utils/summary.js';

const CSV = open(`${DATA_DIR}/cartera-volumen.csv`, 'b');
const XLSX = open(`${DATA_DIR}/cartera-volumen.xlsx`, 'b');

export const options = opciones.volume;

export function setup() {
  exigirEntornoDePruebas();
  const empresa = sesionEmpresaObligatoria();
  const disponible = campanaDisponible(empresa);
  if (!disponible.ok) fail(`no se puede revisar cartera: ${disponible.motivo}`);
  return { empresa };
}

export default function (datos) {
  const marca = `${__VU}-${__ITER}-${Date.now()}`;

  medir('cartera_csv', revisar(datos.empresa, CSV, 'cartera-volumen.csv', TIPO_CSV, {
    lote: `VOL-CSV-${marca}`,
  }), { valida: esRevisionCartera });

  medir('cartera_xlsx', revisar(datos.empresa, XLSX, 'cartera-volumen.xlsx', TIPO_XLSX, {
    lote: `VOL-XLSX-${marca}`,
  }), { valida: esRevisionCartera });

  volumen.add(1, { formato: 'csv' });
  volumen.add(1, { formato: 'xlsx' });
}

export function handleSummary(data) {
  return resumen(data, 'volume');
}
