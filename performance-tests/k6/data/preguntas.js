// Preguntas ficticias para el asistente. No traen datos personales ni reales.
export const PREGUNTAS = [
  '¿Cuánto debo en total?',
  '¿Puedo pagar en cuotas?',
  '¿Cuándo vence mi próxima cuota?',
  '¿Qué pasa si no pago este mes?',
  '¿Cómo puedo pagar mi deuda?',
];

export function preguntaAleatoria() {
  return PREGUNTAS[Math.floor(Math.random() * PREGUNTAS.length)];
}
