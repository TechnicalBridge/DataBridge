// =============================================================================
//  Operaciones sobre deudas.
// =============================================================================
//  Solo las rutas que existen en ms-debt: lista, detalle, simulacion,
//  repactacion, historial de pagos, vencimientos y el resumen de la empresa.
//  Las funciones no miden: devuelven la respuesta y el escenario decide como
//  contarla (por ejemplo, un 409 al repactar una deuda ya pagada no es una
//  falla de infraestructura).
// =============================================================================
import http from 'k6/http';
import { PORTAL } from '../config/env.js';

function conToken(token) {
  return { headers: { Authorization: `Bearer ${token}` } };
}

export function listar(token, base = PORTAL) {
  return http.get(`${base}/api/debts`, conToken(token));
}

export function obtener(token, id, base = PORTAL) {
  return http.get(`${base}/api/debts/${id}`, conToken(token));
}

export function simular(token, id, meses = 6, base = PORTAL) {
  const plazo = Math.max(3, Math.min(24, Math.trunc(meses)));
  return http.get(`${base}/api/debts/${id}/simulate?months=${plazo}`, conToken(token));
}

export function repactar(token, id, meses = 6, base = PORTAL) {
  const plazo = Math.max(3, Math.min(24, Math.trunc(meses)));
  return http.post(`${base}/api/debts/${id}/repact`, JSON.stringify({ months: plazo }), {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
  });
}

export function historialPagos(token, base = PORTAL) {
  return http.get(`${base}/api/debts/pagos`, conToken(token));
}

export function vencimientos(token, base = PORTAL) {
  return http.get(`${base}/api/debts/vencimientos`, conToken(token));
}

export function resumenEmpresa(token, base = PORTAL) {
  return http.get(`${base}/api/analytics/summary`, conToken(token));
}

export function enRiesgo(token, base = PORTAL) {
  return http.get(`${base}/api/debts/en-riesgo`, conToken(token));
}

export function deudasDe(respuesta) {
  try {
    return respuesta.json('_embedded.debts') || [];
  } catch (error) {
    return [];
  }
}

export function elegirDeuda(deudas, externalId) {
  if (!Array.isArray(deudas) || deudas.length === 0) return null;
  if (externalId) {
    const buscada = deudas.find((deuda) => deuda.externalId === externalId);
    if (buscada) return buscada;
  }
  return deudas.find((deuda) => !['paid', 'withdrawn', 'disputed'].includes(deuda.estado)) || deudas[0];
}

export function elegirRepactable(deudas, externalId) {
  if (!Array.isArray(deudas) || deudas.length === 0) return null;
  if (externalId) {
    const buscada = deudas.find((deuda) => deuda.externalId === externalId);
    if (buscada && !['paid', 'withdrawn', 'disputed'].includes(buscada.estado)) return buscada;
  }
  return deudas.find((deuda) => ['repacted', 'open'].includes(deuda.estado))
    || deudas.find((deuda) => !['paid', 'withdrawn', 'disputed'].includes(deuda.estado))
    || null;
}
