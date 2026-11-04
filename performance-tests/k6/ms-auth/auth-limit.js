// =============================================================================
//  Las dos capas que frenan a quien prueba codigos de acceso.
// =============================================================================
//  debt-load.js, debt-stress.js, gateway-load.js y payment-load.js miden el
//  sistema con el limite subido a proposito. Este mide los frenos mismos, con
//  los valores de fabrica. Es a la
//  vez una prueba de rendimiento y una de seguridad: lo que impide que alguien
//  pruebe codigos de acceso a mil por minuto es exactamente esto.
//
//  Son dos capas, y cada una responde 429 con su propio mensaje:
//
//    1. El GATEWAY cuenta peticiones por IP: 10 por minuto en /api/auth/**.
//       No sabe si el codigo era correcto; solo cuenta.
//
//    2. MS-AUTH cuenta INTENTOS FALLIDOS por IP: 10 en diez minutos y bloquea.
//       Es la que de verdad importa contra la fuerza bruta, porque el cupo
//       del gateway se recarga cada seis segundos.
//
//  La prueba manda codigos equivocados y comprueba las dos: el gateway corta
//  en la peticion 11, se recarga solo, y cuando la siguiente llega a ms-auth,
//  ms-auth la frena por su cuenta porque ese origen ya fallo diez veces.
//
//      docker compose --profile carga run --rm k6 run /performance-tests/k6/ms-auth/auth-limit.js
// =============================================================================
import http from 'k6/http';
import { check, sleep } from 'k6';
import { CUPO_AUTH, PORTAL, opciones } from '../config/options.js';

//  Cada capa se reconoce por su mensaje: las dos responden 429.
const DEL_GATEWAY = 'Demasiadas solicitudes';
const DE_MS_AUTH = 'Demasiados intentos';

export const options = opciones.authLimit;

const json = { headers: { 'Content-Type': 'application/json' } };

//  Un RUT que existe como formato pero no tiene codigo: lo que se mide es el
//  freno, no si el codigo era correcto.
const intento = () => http.post(`${PORTAL}/api/auth/acceso`,
  JSON.stringify({ rut: '11111111-1', codigo: 'XXXXXX' }), json);

const frenoDelGateway = (r) => r.status === 429 && `${r.body}`.includes(DEL_GATEWAY);

export default function () {
  //  1. El gateway corta.
  let corte = null;
  for (let i = 1; i <= CUPO_AUTH + 5; i++) {
    if (frenoDelGateway(intento())) { corte = i; break; }
  }
  check(corte, {
    [`el gateway corta en la peticion ${CUPO_AUTH + 1}`]: (n) => n === CUPO_AUTH + 1,
  });

  //  2. Y se recarga solo: un limitador que bloquea para siempre no es una
  //     proteccion sino una caida. Se prueba cada 3 segundos hasta un minuto.
  let despues = null;
  for (let intentoN = 0; intentoN < 20; intentoN++) {
    sleep(3);
    const r = intento();
    if (!frenoDelGateway(r)) { despues = r; break; }
  }
  check(despues, { 'el cupo del gateway se recarga solo': (r) => r !== null });

  //  3. Pero esa peticion, que ya paso el gateway, la frena ms-auth: este
  //     origen fallo diez codigos y queda bloqueado diez minutos, por mucho que
  //     el gateway se recargue.
  check(despues, {
    'ms-auth frena por su cuenta al origen que ya fallo diez veces':
      (r) => r !== null && r.status === 429 && `${r.body}`.includes(DE_MS_AUTH),
  });

  console.log(`gateway: corte en la peticion ${corte} (cupo ${CUPO_AUTH}) · `
    + `despues de recargar: ${despues ? despues.status + ' ' + despues.body : 'nunca se recargo'}`);
}
