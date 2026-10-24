import axios from "axios";

/**
 * El cliente HTTP del portal y la sesion.
 *
 * La sesion vive en dos piezas. El JWT, que dura quince minutos, se guarda EN
 * MEMORIA: en una variable de este modulo, que muere al cerrar la pestana.
 * Antes vivia en localStorage, donde cualquier script de la pagina lo podia
 * leer y llevarselo. La llave de renovacion la guarda el navegador en una
 * cookie que este codigo no puede leer: solo sabe pedir "dame el JWT siguiente".
 *
 * Las paginas no usan este modulo directo: llaman a las funciones de
 * sesion.js, deudas.js, pagos.js, analitica.js y asistente.js.
 */
let token = null;

//  Las versiones anteriores dejaban aca un JWT de siete dias. Se borra al
//  cargar, para que no quede uno viejo y vigente en ningun navegador.
try {
  localStorage.removeItem("tb_session");
} catch {
  /* sin acceso al almacenamiento: no hay nada que borrar */
}

export function setSessionToken(nuevo) {
  token = nuevo || null;
}

let alPerderLaSesion = () => {};

/** Lo que hay que hacer cuando la sesion ya no se puede renovar. */
export function onSesionPerdida(fn) {
  alPerderLaSesion = fn;
}

let renovando = null;

/**
 * Pide un JWT nuevo con la cookie.
 *
 * Si varias peticiones vencen a la vez, todas esperan la MISMA renovacion. Si
 * responde 409 —otra pestana la acaba de renovar— se reintenta una vez: para
 * entonces el navegador ya tiene la cookie nueva.
 */
export function renovarSesion() {
  if (!renovando) {
    const pedir = () => axios.post("/api/auth/refresh");
    renovando = pedir()
      .catch((err) => {
        if (err.response?.status !== 409) throw err;
        return new Promise((listo) => setTimeout(listo, 300)).then(pedir);
      })
      .then((res) => {
        setSessionToken(res.data.token);
        return res.data;
      })
      .finally(() => {
        renovando = null;
      });
  }
  return renovando;
}

/**
 * Los servicios responden los errores de dos formas: { error: "texto" } y,
 * en el borde de integracion, { error: { codigo, mensaje } }. La pantalla
 * necesita el texto y, si una planilla trae varios problemas, cada uno
 * (error.detalles).
 */
function comoError(err) {
  const data = err.response?.data;
  const mensaje =
    data?.error?.mensaje || (typeof data?.error === "string" ? data.error : null) ||
    data?.message || err.message || "Error de red";
  const error = new Error(mensaje);
  error.status = err.response?.status;
  error.detalles = Array.isArray(data?.error?.detalles) ? data.error.detalles : [];
  return error;
}

/** Con la sesion: todo lo que esta detras del login. */
export const client = axios.create({
  baseURL: "/api",
  headers: { "Content-Type": "application/json" },
});

/** Sin sesion: la pagina de la pasarela, que se abre con la firma del pago. */
export const publico = axios.create({ baseURL: "/api" });
publico.interceptors.response.use((res) => res, (err) => Promise.reject(comoError(err)));

/** Las rutas de /auth consiguen la sesion: no la llevan ni la renuevan. */
const esDeAutenticacion = (config) => String(config?.url || "").startsWith("/auth/");

client.interceptors.request.use((config) => {
  if (token && !esDeAutenticacion(config)) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  // Un archivo va como multipart: el navegador pone el Content-Type con su
  // separador, y el JSON por defecto lo romperia.
  if (typeof FormData !== "undefined" && config.data instanceof FormData) {
    delete config.headers["Content-Type"];
  }
  return config;
});

client.interceptors.response.use(
  (res) => res,
  async (err) => {
    //  Un 401 en medio de algo casi siempre es el JWT que vencio: se renueva
    //  y se repite la peticion UNA vez. Si renovar tampoco funciona, la sesion
    //  se termino de verdad y la pantalla vuelve al inicio.
    const original = err.config;
    if (err.response?.status === 401 && original && !original._reintento && !esDeAutenticacion(original)) {
      original._reintento = true;
      try {
        await renovarSesion();
        return client.request(original);
      } catch {
        setSessionToken(null);
        alPerderLaSesion();
      }
    }
    return Promise.reject(comoError(err));
  }
);

/** Una peticion con sesion; devuelve el cuerpo. */
export async function pedir(ruta, { method = "GET", body, params } = {}) {
  const res = await client.request({ url: ruta, method, data: body, params });
  return res.data;
}

/**
 * Las colecciones vienen en HAL: la lista esta en _embedded.<nombre>. Una
 * coleccion vacia no trae _embedded, y eso es una lista vacia, no un error.
 */
export const embebidos = (data, nombre) => data?._embedded?.[nombre] ?? [];
