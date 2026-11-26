// =============================================================================
//  Validaciones compartidas.
// =============================================================================
//  Una respuesta 200 no siempre significa que la operacion sirvio. La lista
//  de deudas viene en HAL (_embedded.debts), la simulacion trae un plan y la
//  revision de cartera trae el conteo. Estas funciones comprueban la forma,
//  no solo el codigo.
// =============================================================================

function json(respuesta, ruta) {
  try {
    return respuesta.json(ruta);
  } catch (error) {
    return undefined;
  }
}

export const es2xx = (respuesta) => respuesta.status >= 200 && respuesta.status < 300;

/**
 * Una coleccion HAL. Spring omite `_embedded` cuando la lista viene vacia:
 * en ese caso la respuesta valida es la que trae su enlace `self`, no una
 * que invente un `_embedded` vacio.
 */
function esColeccion(respuesta, clave) {
  if (respuesta.status !== 200) return false;
  const contenido = json(respuesta, `_embedded.${clave}`);
  if (Array.isArray(contenido)) return true;
  return contenido === undefined && json(respuesta, '_links.self') !== undefined;
}

export const esListaDeudas = (respuesta) => esColeccion(respuesta, 'debts');

export const traeDeudas = (respuesta) => {
  if (!esListaDeudas(respuesta)) return false;
  return (json(respuesta, '_embedded.debts') || []).length > 0;
};

export const esDetalleDeuda = (respuesta) => respuesta.status === 200
  && json(respuesta, 'id') !== undefined
  && json(respuesta, 'externalId') !== undefined;

export const esSimulacion = (respuesta) => respuesta.status === 200
  && json(respuesta, 'plan') !== undefined
  && Number(json(respuesta, 'plan.months')) >= 3;

export const esRepactacion = (respuesta) => respuesta.status === 200
  || respuesta.status === 409;

export const esHistorial = (respuesta) => esColeccion(respuesta, 'pagos');

export const esListaPagos = (respuesta) => esColeccion(respuesta, 'payments');

export const esVencimientos = (respuesta) => esColeccion(respuesta, 'cuotas');

export const esSaludIA = (respuesta) => respuesta.status === 200
  && json(respuesta, 'ok') === true;

export const esChat = (respuesta) => respuesta.status === 200
  && typeof json(respuesta, 'reply') === 'string'
  && json(respuesta, 'reply').length > 0;

export const esMe = (respuesta) => respuesta.status === 200
  && json(respuesta, 'user.rut') !== undefined;

export const esOpcionesCartera = (respuesta) => respuesta.status === 200
  && Array.isArray(json(respuesta, 'acreedores'));

export const esRevisionCartera = (respuesta) => {
  if (respuesta.status !== 200) return false;
  const cartera = json(respuesta, 'cartera');
  return cartera !== undefined
    && Number(cartera.recibidas) > 0
    && Number(cartera.rechazadas) === 0;
};

export const esResumen = (respuesta) => respuesta.status === 200
  && json(respuesta, '_links') !== undefined;
