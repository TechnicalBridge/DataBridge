// =============================================================================
//  10. Database performance testing (indirecto).
// =============================================================================
//  No existe un endpoint SQL publico y no se inventa. Lo que se mide son las
//  lecturas de ms-debt que mas trabajan contra MySQL:
//
//    GET /api/debts                 lista con saldo y cuotas
//    GET /api/debts/{id}            detalle con cargos, cuotas e historia
//    GET /api/debts/pagos           historial de pagos
//    GET /api/debts/vencimientos    cuotas por vencer del deudor
//    GET /api/analytics/summary     agregaciones de la cartera de la empresa
//
//  Se pega directo a ms-debt:8083 para quitar nginx y gateway de la ecuacion.
//  Si la base se degrada, estas rutas lo muestran antes que las demas.
// =============================================================================
import { sleep } from 'k6';
import { DEBT, exigirEntornoDePruebas } from '../config/env.js';
import { opciones } from '../config/scenarios.js';
import { sesionDeudor, sesionEmpresaObligatoria } from '../modules/auth.js';
import { datosDeDeudas } from '../modules/journey.js';
import {
  historialPagos,
  listar,
  obtener,
  resumenEmpresa,
  vencimientos,
} from '../modules/debts.js';
import {
  esDetalleDeuda,
  esHistorial,
  esListaDeudas,
  esResumen,
  esVencimientos,
} from '../utils/checks.js';
import { medir } from '../utils/http.js';
import { resumen } from '../utils/summary.js';

export const options = opciones.database;

export function setup() {
  exigirEntornoDePruebas();
  const deudor = sesionDeudor();
  const empresa = sesionEmpresaObligatoria();
  return datosDeDeudas(deudor, empresa);
}

export default function (datos) {
  medir('db_lista', listar(datos.deudor, DEBT), { valida: esListaDeudas });
  medir('db_detalle', obtener(datos.deudor, datos.deuda.id, DEBT), { valida: esDetalleDeuda });
  medir('db_historial', historialPagos(datos.deudor, DEBT), { valida: esHistorial });
  medir('db_vencimientos', vencimientos(datos.deudor, DEBT), { valida: esVencimientos });
  medir('db_resumen', resumenEmpresa(datos.empresa, DEBT), { valida: esResumen });
  sleep(0.2);
}

export function handleSummary(data) {
  return resumen(data, 'database-performance');
}
