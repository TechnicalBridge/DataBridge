// =============================================================================
//  Cuánto aguanta el asistente.
// =============================================================================
//  Dos rutas de ms-ai:
//
//      GET  /api/ai/salud   si el servicio está arriba y qué proveedor usa
//      POST /api/ai/chat    una pregunta del deudor sobre sus deudas
//
//  El chat lee la cartera desde ms-debt con el mismo JWT, así que cada
//  pregunta cuesta más que una lectura simple. Con LLM_PROVIDER=local responde
//  con reglas y no sale a internet; con Groq, Gemini o xAI la latencia la pone
//  el proveedor y el umbral deja de ser comparable.
//
//      docker compose --profile carga run --rm k6 run /performance-tests/k6/ms-ai/ai-load.js
// =============================================================================
import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Trend } from 'k6/metrics';
import { AI, RUT_DEUDOR, json, opciones, sesionDeudor } from '../config/options.js';

const t = {
  salud: new Trend('ai_salud', true),
  chat: new Trend('ai_chat', true),
};

//  Preguntas que el motor local entiende sin depender de un LLM.
const PREGUNTAS = [
  '¿Cuánto debo?',
  '¿A quién le debo?',
  '¿Puedo pagar en cuotas?',
];

export const options = opciones.aiLoad;

export function setup() {
  const token = sesionDeudor(RUT_DEUDOR, 'asistente@prueba.local', 'Prueba de ms-ai');

  //  Calentamiento: el primer chat carga reglas y despierta el cliente de
  //  ms-debt. Se deja fuera de los Trend para no medir el arranque.
  const salud = http.get(`${AI}/api/ai/salud`);
  if (salud.status !== 200) fail(`GET /api/ai/salud no responde 200: ${salud.status}`);
  const chat = http.post(`${AI}/api/ai/chat`,
    JSON.stringify({ message: '¿Cuánto debo?', messages: [] }),
    json({ headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } }));
  if (chat.status !== 200) fail(`POST /api/ai/chat no responde 200: ${chat.status} ${chat.body}`);
  return { token };
}

export default function (datos) {
  const salud = http.get(`${AI}/api/ai/salud`, { tags: { ruta: 'salud' } });
  t.salud.add(salud.timings.duration);
  check(salud, {
    'GET /api/ai/salud responde 200 y está arriba': (r) => r.status === 200 && r.json('ok') === true,
  });

  const pregunta = PREGUNTAS[Math.floor(Math.random() * PREGUNTAS.length)];
  const chat = http.post(`${AI}/api/ai/chat`,
    JSON.stringify({ message: pregunta, messages: [] }),
    json({
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${datos.token}` },
      tags: { ruta: 'chat' },
    }));
  t.chat.add(chat.timings.duration);
  check(chat, {
    'POST /api/ai/chat responde 200 con una respuesta': (r) => {
      if (r.status !== 200) return false;
      return `${r.json('reply') || ''}`.length > 0;
    },
  });

  //  Una persona escribe una pregunta y lee la respuesta antes de la siguiente.
  sleep(1);
}
