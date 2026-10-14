import { pedir } from "./client";

/**
 * El panel de administracion de DataBridge. Todo lo que cuelga de /api/admin
 * exige la sesion de un admin: a una empresa o a un deudor les responde 403.
 */

/** Los servicios a los que el panel les pregunta si estan arriba. */
export const SERVICIOS = [
  { tramo: "auth", nombre: "Acceso", servicio: "ms-auth", que: "Códigos, enlaces y sesiones" },
  { tramo: "debt", nombre: "Deudas", servicio: "ms-debt", que: "Cartera, convenios y campañas" },
  { tramo: "payments", nombre: "Pagos", servicio: "ms-payments", que: "Cobros y pasarelas" },
];

/** Si un servicio responde. */
export const estadoDe = (tramo) => pedir(`/admin/${tramo}/estado`);
