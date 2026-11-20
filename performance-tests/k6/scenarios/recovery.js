// =============================================================================
//  11. Recovery testing: despues del golpe, vuelve.
// =============================================================================
//  Primero 30 segundos de sobrecarga a 300 peticiones por segundo sobre la
//  lista de deudas. Despues una pausa de 15 s y 30 segundos de carga normal
//  (10 usuarios con una pausa de un segundo entre lecturas).
//
//  El umbral que importa es el de la fase "recuperacion": si el p95 no vuelve
//  por debajo de un segundo y la tasa de exito no sube a 99 %, el sistema
//  aguanto el golpe pero no se recupero.
// =============================================================================
import exec from 'k6/execution';
import { sleep } from 'k6';
import { exigirEntornoDePruebas } from '../config/env.js';
import { opciones } from '../config/scenarios.js';
import { sesionDeudor } from '../modules/auth.js';
import { datosDeDeudas } from '../modules/journey.js';
import { listar, obtener } from '../modules/debts.js';
import { esDetalleDeuda, esListaDeudas } from '../utils/checks.js';
import { medir } from '../utils/http.js';
import { resumen } from '../utils/summary.js';

export const options = opciones.recovery;

export function setup() {
  exigirEntornoDePruebas();
  const deudor = sesionDeudor();
  return datosDeDeudas(deudor);
}

export default function (datos) {
  const fase = exec.scenario.name;
  medir('recovery_lista', listar(datos.deudor), {
    valida: esListaDeudas,
    etiquetas: { fase },
  });
  if (fase === 'recuperacion') {
    medir('recovery_detalle', obtener(datos.deudor, datos.deuda.id), {
      valida: esDetalleDeuda,
      etiquetas: { fase },
    });
    //  Una persona no pide la lista en bucle: la fase de recuperacion simula
    //  el ritmo normal, no otro ataque.
    sleep(1);
  }
}

export function handleSummary(data) {
  return resumen(data, 'recovery');
}
