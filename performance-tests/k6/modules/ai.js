// =============================================================================
//  El asistente de ms-ai.
// =============================================================================
//  La salud es publica. El chat necesita la sesion del deudor: ms-ai lee su
//  cartera en ms-debt con el mismo JWT. Con LLM_PROVIDER=local responde con
//  reglas y no sale a internet; con un proveedor real la latencia deja de ser
//  comparable y por eso la suite permite apagar el chat con INCLUIR_IA=false.
// =============================================================================
import http from 'k6/http';
import { API, PORTAL } from '../config/env.js';
import { preguntaAleatoria } from '../data/preguntas.js';

export function salud(base = API) {
  return http.get(`${base}/api/ai/salud`);
}

export function chat(token, mensaje = preguntaAleatoria(), base = PORTAL) {
  return http.post(`${base}/api/ai/chat`,
    JSON.stringify({ message: mensaje, messages: [] }),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
    });
}
