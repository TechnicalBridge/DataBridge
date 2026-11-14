// =============================================================================
//  Humo: la pila esta arriba y la suite puede hablar con ella.
// =============================================================================
//  No es una de las doce pruebas de carga. Es el recorrido minimo que conviene
//  correr antes: sesion de deudor, sesion de empresa, lectura de deudas, pagos,
//  asistente, resumen y una revision pequena de cartera en CSV y XLSX.
//
//  La revision de cartera hace rollback siempre; no deja datos. Las carteras
//  son ficticias y se generan con scripts/generar-datos.py.
//
//      docker compose --profile carga run --rm k6 run /performance-tests/k6/scenarios/smoke.js
// =============================================================================
import http from 'k6/http';
import { fail } from 'k6';
import { DATA_DIR, INCLUIR_IA, PORTAL, VERIFICAR_PASARELAS, exigirEntornoDePruebas } from '../config/env.js';
import { opciones } from '../config/scenarios.js';
import { sesionDeudor, sesionEmpresaObligatoria } from '../modules/auth.js';
import { campanaDisponible, plantilla, revisar, TIPO_CSV, TIPO_XLSX } from '../modules/cartera.js';
import { datosDeDeudas, recorridoCompleto } from '../modules/journey.js';
import { PASARELAS, abrirCobro, esSimulado } from '../modules/payments.js';
import { medir } from '../utils/http.js';
import { es2xx, esOpcionesCartera, esRevisionCartera } from '../utils/checks.js';
import { resumen } from '../utils/summary.js';

const CSV = open(`${DATA_DIR}/cartera-pequena.csv`, 'b');
const XLSX = open(`${DATA_DIR}/cartera-pequena.xlsx`, 'b');

export const options = opciones.smoke;

export function setup() {
  exigirEntornoDePruebas();
  const deudor = sesionDeudor();
  const empresa = sesionEmpresaObligatoria();
  const datos = datosDeDeudas(deudor, empresa);
  const disponible = campanaDisponible(empresa);
  if (!disponible.ok) fail(`no se puede revisar cartera: ${disponible.motivo}`);
  return datos;
}

export default function (datos) {
  //  El portal sirve el index de React. No es una ruta de API, pero si esto
  //  falla no tiene sentido seguir.
  medir('smoke_portal', http.get(`${PORTAL}/`), {
    valida: (r) => r.status === 200 && `${r.body}`.includes('id="root"'),
  });

  recorridoCompleto(datos, 'smoke', { pausa: 0, incluirHistorial: true });

  if (VERIFICAR_PASARELAS) {
    //  Un cobro por pasarela: comprueba que la pagina sea la simulada de
    //  DataBridge y no la de Transbank, Khipu o Mercado Pago. No confirma el
    //  pago; los cobros quedan abiertos en la base local.
    for (const pasarela of PASARELAS) {
      medir(`smoke_pasarela_${pasarela}`,
        abrirCobro(datos.deudor, datos.deuda.id, pasarela),
        { valida: esSimulado });
    }
  }

  medir('smoke_cartera_opciones',
    http.get(`${PORTAL}/api/debts/cartera/opciones`, {
      headers: { Authorization: `Bearer ${datos.empresa}` },
    }),
    { valida: esOpcionesCartera });

  medir('smoke_cartera_plantilla', plantilla(datos.empresa), {
    valida: (r) => es2xx(r) && `${r.headers['Content-Type'] || ''}`.includes('spreadsheetml'),
  });

  medir('cartera_smoke_csv', revisar(datos.empresa, CSV, 'cartera-pequena.csv', TIPO_CSV, {
    lote: `SMOKE-CSV-${Date.now()}`,
  }), { valida: esRevisionCartera });

  medir('cartera_smoke_xlsx', revisar(datos.empresa, XLSX, 'cartera-pequena.xlsx', TIPO_XLSX, {
    lote: `SMOKE-XLSX-${Date.now()}`,
  }), { valida: esRevisionCartera });

  if (!INCLUIR_IA) {
    console.log('INCLUIR_IA=false: se omite el chat del asistente');
  }
}

export function handleSummary(data) {
  return resumen(data, 'smoke');
}
