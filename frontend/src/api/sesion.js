import { pedir } from "./client";

/** Entrar con codigo y RUT del deudor o correo del personal. */
export const entrarConCodigo = (identificador, codigo, esDeudor = true) =>
  pedir("/auth/acceso", { method: "POST", body: { [esDeudor ? "rut" : "correo"]: identificador, codigo } });

export const pedirCodigo = (correo, rut) =>
  pedir("/auth/codigo", { method: "POST", body: { correo, rut: rut || null } });

/** Codigo nuevo y enlace de respaldo, juntos en el mismo correo. */
export const pedirEnlace = (correo, rut) =>
  pedir("/auth/enlace", { method: "POST", body: { correo, rut: rut || null } });

/** Canjear el enlace del correo por una sesion. */
export const entrarConEnlace = (token) => pedir("/auth/verify", { method: "POST", body: { token } });

/** Quien es el dueno del JWT. */
export const yo = () => pedir("/me").then((data) => data.user);

/** Cerrar la sesion en el servidor: revoca la llave de renovacion. */
export const cerrarSesion = () => pedir("/auth/logout", { method: "POST" });
