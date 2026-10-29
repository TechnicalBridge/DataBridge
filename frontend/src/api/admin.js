import { embebidos, pedir } from "./client";

/**
 * El panel de administracion de DataBridge. Todo lo que cuelga de /api/admin
 * exige la sesion de un admin: a una empresa o a un deudor les responde 403.
 *
 * Una empresa vive en ms-debt y sus personas en ms-auth, unidas por el RUT:
 * por eso cada cosa se le pide a su servicio.
 */

/** Los servicios a los que el panel les pregunta si estan arriba. */
export const SERVICIOS = [
  { tramo: "auth", nombre: "Acceso", servicio: "ms-auth", que: "Códigos, enlaces y sesiones" },
  { tramo: "debt", nombre: "Deudas", servicio: "ms-debt", que: "Cartera, convenios y campañas" },
  { tramo: "payments", nombre: "Pagos", servicio: "ms-payments", que: "Cobros y pasarelas" },
];

/** Si un servicio responde. */
export const estadoDe = (tramo) => pedir(`/admin/${tramo}/estado`);

/** Las reglas que rigen en un servicio, agrupadas, y de que variable sale cada una. */
export const reglasDe = (tramo) => pedir(`/admin/${tramo}/reglas`);

// ---- Las empresas (ms-debt) ----------------------------------------------------

/** Todas las empresas, con los totales de su cartera. Ningun dato de un deudor. */
export const listarEmpresas = () => pedir("/admin/debt/empresas").then((data) => embebidos(data, "empresas"));

export const obtenerEmpresa = (rut) => pedir(`/admin/debt/empresas/${encodeURIComponent(rut)}`);

/** Dar de alta una empresa usuaria: una agencia, o un acreedor que cobra directo. */
export const crearEmpresa = (empresa) => pedir("/admin/debt/empresas", { method: "POST", body: empresa });

/** Suspender una empresa usuaria: corta su acceso y el de sus personas y claves de API. */
export const suspenderEmpresa = (rut) =>
  pedir(`/admin/debt/empresas/${encodeURIComponent(rut)}/suspender`, { method: "POST" });

/** Reactivar una empresa usuaria previamente suspendida. */
export const reactivarEmpresa = (rut) =>
  pedir(`/admin/debt/empresas/${encodeURIComponent(rut)}/reactivar`, { method: "POST" });

// ---- Las personas (ms-auth) ----------------------------------------------------

export const listarPersonal = (rut) =>
  pedir(`/admin/auth/empresas/${encodeURIComponent(rut)}/personal`).then((data) => embebidos(data, "personas"));

/** Le da acceso a una persona, como usuario de esa empresa. Le llega su invitacion al correo. */
export const crearPersona = (rut, persona) =>
  pedir(`/admin/auth/empresas/${encodeURIComponent(rut)}/personal`, { method: "POST", body: persona });

export const listarAdmins = () => pedir("/admin/auth/admins").then((data) => embebidos(data, "personas"));

/** Le da acceso al panel a otra persona de DataBridge. */
export const crearAdmin = (persona) => pedir("/admin/auth/admins", { method: "POST", body: persona });

export const quitarAcceso = (id) => pedir(`/admin/auth/personal/${id}/deshabilitar`, { method: "POST" });

export const devolverAcceso = (id) => pedir(`/admin/auth/personal/${id}/habilitar`, { method: "POST" });

// ---- Lo que se hizo, y quien -----------------------------------------------------

const acciones = (data) => embebidos(data, "acciones");
const recientePrimero = (a, b) => new Date(b.cuando) - new Date(a.cuando);

/**
 * La historia de una empresa en el panel. La alta de la empresa la anota
 * ms-debt y lo que se hizo con sus personas, ms-auth: se juntan aca.
 */
export async function historialDeEmpresa(rut) {
  const ruta = encodeURIComponent(rut);
  const [deLaEmpresa, deSusPersonas] = await Promise.all([
    pedir(`/admin/debt/empresas/${ruta}/historial`).then(acciones),
    pedir(`/admin/auth/empresas/${ruta}/historial`).then(acciones),
  ]);
  return [...deLaEmpresa, ...deSusPersonas].sort(recientePrimero);
}

export const historialDeAdmins = () => pedir("/admin/auth/admins/historial").then(acciones);

// ---- La bitacora de acceso (ms-auth, #141) ----------------------------------------

/**
 * Una pagina de la bitacora de acceso, lo mas reciente primero: las filas y la
 * pagina (number, totalPages, totalElements). Los filtros vacios no se mandan.
 * El RUT llega a medias y no viene ni correo ni IP.
 */
export async function accesos({ desde, hasta, metodo, resultado, pagina = 0, porPagina = 50 } = {}) {
  const params = { pagina, porPagina };
  if (desde) params.desde = desde;
  if (hasta) params.hasta = hasta;
  if (metodo) params.metodo = metodo;
  if (resultado) params.resultado = resultado;
  const data = await pedir("/admin/auth/accesos", { params });
  return { filas: embebidos(data, "accesos"), pagina: data.page };
}

/** Los totales de hoy, en Chile: cuantos entraron, cuantos rechazos y cuantos pedidos salieron. */
export const accesosDeHoy = () => pedir("/admin/auth/accesos/hoy");

// ---- La operacion (ms-debt y ms-payments, #139) ----------------------------------

/** Los avisos a las empresas que no se entregaron, por destinatario. Sin su contenido. */
export const avisosAEmpresas = () => pedir("/admin/debt/avisos");

/** Vuelve a poner en cola un aviso a una empresa; el despachador lo toma en su pasada siguiente. */
export const reintentarAviso = (id) => pedir(`/admin/debt/avisos/${id}/reintentar`, { method: "POST" });

/** Vuelve a poner en cola todos los avisos fallidos de un destinatario. */
export const reintentarDestino = (suscripcion) =>
  pedir(`/admin/debt/avisos/destinos/${suscripcion}/reintentar`, { method: "POST" });

/** Los pagos que hay que mirar, la UF de hoy, los avisos de pago a ms-debt y lo ultimo que se hizo. */
export const operacionDePagos = () => pedir("/admin/payments/operacion");

/** Vuelve a poner en cola un aviso de pago a ms-debt. */
export const reintentarAvisoDePago = (id) => pedir(`/admin/payments/avisos/${id}/reintentar`, { method: "POST" });

/** Carga a mano la UF de un dia: queda como la de ese dia, y se anota quien la cargo. */
export const cargarUf = (dia, valor) => pedir("/admin/payments/uf", { method: "POST", body: { dia, valor } });

/** Lo que DataBridge le cobra a cada empresa en un mes (2026-10): el modelo de negocio, #200. */
export const comisionesDelMes = (mes) => pedir("/admin/debt/comisiones", { params: { mes } });
