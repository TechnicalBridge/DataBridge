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
