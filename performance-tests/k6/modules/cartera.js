// =============================================================================
//  Carga y revision de cartera.
// =============================================================================
//  La ruta que se mide es POST /api/debts/cartera/revision: pasa la planilla
//  por la misma ingesta que una carga real, pero siempre hace rollback. No
//  deja deudas, lotes, contactos ni correos. Por eso se puede repetir.
//
//  Los archivos son ficticios y se generan con scripts/generar-datos.py.
// =============================================================================
import http from 'k6/http';
import {
  CAMPANA_ID,
  PORTAL,
  RUT_EMPRESA,
} from '../config/env.js';

export const TIPO_CSV = 'text/csv';
export const TIPO_XLSX = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

export function opciones(token, base = PORTAL) {
  return http.get(`${base}/api/debts/cartera/opciones`, {
    headers: { Authorization: `Bearer ${token}` },
  });
}

export function plantilla(token, base = PORTAL) {
  return http.get(`${base}/api/debts/cartera/plantilla`, {
    headers: { Authorization: `Bearer ${token}` },
  });
}

/**
 * Revisa una cartera sin guardar nada.
 *
 * @param token sesion de empresa
 * @param archivo ArrayBuffer de open(..., 'b')
 * @param nombre nombre visible del archivo
 * @param tipo Content-Type del archivo
 * @param extra  { campana, acreedor, lote }
 */
export function revisar(token, archivo, nombre, tipo, extra = {}) {
  const formulario = {
    archivo: http.file(archivo, nombre, tipo),
    acreedor_rut: extra.acreedor || RUT_EMPRESA,
    campana_id_externo: extra.campana || CAMPANA_ID,
  };
  if (extra.lote) formulario.lote_id_externo = extra.lote;

  return http.post(`${PORTAL}/api/debts/cartera/revision`, formulario, {
    headers: { Authorization: `Bearer ${token}` },
    timeout: '60s',
  });
}

/** Comprueba que la empresa de la sesion puede cargar en la campana configurada. */
export function campanaDisponible(token, base = PORTAL) {
  const respuesta = opciones(token, base);
  if (respuesta.status !== 200) {
    return { ok: false, motivo: `opciones de cartera respondio ${respuesta.status}: ${respuesta.body}` };
  }
  let cuerpo;
  try {
    cuerpo = respuesta.json();
  } catch (error) {
    return { ok: false, motivo: 'opciones de cartera no trajo JSON' };
  }
  const acreedor = (cuerpo.acreedores || []).find((a) => a.rut === RUT_EMPRESA);
  if (!acreedor) {
    return {
      ok: false,
      motivo: `la empresa ${RUT_EMPRESA} no aparece entre los acreedores de la sesion: ${respuesta.body}`,
    };
  }
  if (acreedor.suspendido) {
    return { ok: false, motivo: `la empresa ${RUT_EMPRESA} esta suspendida` };
  }
  const campana = (acreedor.campanas || []).find((c) => c.idExterno === CAMPANA_ID);
  if (!campana) {
    return {
      ok: false,
      motivo: `la campana ${CAMPANA_ID} no existe para ${RUT_EMPRESA}; `
        + `disponibles: ${(acreedor.campanas || []).map((c) => c.idExterno).join(', ') || 'ninguna'}`,
    };
  }
  if (campana.estado === 'terminada') {
    return { ok: false, motivo: `la campana ${CAMPANA_ID} esta terminada` };
  }
  return { ok: true, campana, acreedor };
}
