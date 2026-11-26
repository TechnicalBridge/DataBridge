// =============================================================================
//  Operaciones de ms-payments que se pueden medir sin cobrar de verdad.
// =============================================================================
//  Abrir un cobro escribe en la base, pero no confirma el pago: la deuda sigue
//  viva. Aun asi, la suite no lo usa en carga sostenida; solo lo verifica en el
//  humo si VERIFICAR_PASARELAS=true. Las pruebas de lectura usan el historial.
//
//  Nunca se llama a /confirm ni se sigue una URL de pasarela real.
// =============================================================================
import http from 'k6/http';
import { PORTAL } from '../config/env.js';

export const PASARELAS = ['webpay', 'khipu', 'mercadopago'];

export function listarPagos(token, base = PORTAL) {
  return http.get(`${base}/api/payments`, {
    headers: { Authorization: `Bearer ${token}` },
  });
}

export function consultarPago(token, id, base = PORTAL) {
  return http.get(`${base}/api/payments/${id}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
}

export function abrirCobro(token, deudaId, pasarela = 'webpay', base = PORTAL) {
  return http.post(`${base}/api/payments/checkout`,
    JSON.stringify({ debtId: deudaId, gateway: pasarela }),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
    });
}

/** La pagina de pago tiene que ser la simulada de DataBridge, no una pasarela real. */
export function esSimulado(cobro) {
  let url = '';
  let simulada = false;
  try {
    url = `${cobro.json('checkoutUrl') || ''}`;
    simulada = cobro.json('simulada') === true;
  } catch (error) {
    return false;
  }
  return simulada && !/transbank|khipu\.com|mercadopago|mercadolibre/i.test(url);
}

export function pasarelaAleatoria() {
  return PASARELAS[Math.floor(Math.random() * PASARELAS.length)];
}
