import { create } from "zustand";
import { onSesionPerdida, renovarSesion, setSessionToken } from "../api/client";
import * as sesion from "../api/sesion";

/**
 * La sesion.
 *
 * Todos entran primero con codigo: el deudor usa RUT, el personal usa correo.
 * El respaldo solicitado manda codigo y enlace juntos, con un solo uso.
 *
 * El JWT vive en memoria (ver api.js), asi que una recarga de la pagina lo
 * pierde. Lo que sobrevive es la cookie de renovacion: al arrancar, se le pide
 * un JWT nuevo, y si la cookie ya no sirve, no hay sesion.
 */
export const useAuth = create((set, get) => {
  //  Si una renovacion falla en medio de algo, la sesion se termino: se
  //  vacia el usuario y las rutas protegidas mandan al inicio.
  onSesionPerdida(() => set({ user: null }));

  return {
    user: null,
    ready: false,

    /**
     * Recupera la sesion al cargar la pagina.
     *
     * Si no hay cookie, o la que hay ya no sirve, solo marca que termino: no
     * borra nada. Antes vaciaba el token y el usuario, y si alguien habia
     * entrado mientras tanto (la pagina /magic entra apenas carga), su sesion
     * recien abierta se perdia.
     */
    bootstrap: async () => {
      try {
        await renovarSesion();
        set({ user: await sesion.yo() });
      } catch {
        /* no habia sesion que recuperar */
      } finally {
        set({ ready: true });
      }
    },

    entrarConCodigo: async (identificador, codigo, esDeudor = true) => {
      return get().abrirSesion(await sesion.entrarConCodigo(identificador, codigo, esDeudor));
    },

    pedirCodigo: (correo, rut) => sesion.pedirCodigo(correo, rut),
    pedirEnlace: (correo, rut) => sesion.pedirEnlace(correo, rut),

    entrarConEnlace: async (token) => {
      return get().abrirSesion(await sesion.entrarConEnlace(token));
    },

    // La respuesta del login trae el usuario resumido; /me lo trae completo.
    abrirSesion: async (data) => {
      setSessionToken(data.token);
      const user = await sesion.yo();
      set({ user });
      return user;
    },

    /**
     * Cerrar sesion de verdad: el servidor revoca la llave de renovacion.
     * Antes solo se borraba el token del navegador, y quien lo hubiera copiado
     * seguia adentro una semana.
     *
     * La pantalla sale al instante; si el servidor no alcanza a responder, la
     * llave vence sola, pero se avisa en la consola porque no es lo esperado.
     */
    logout: async () => {
      setSessionToken(null);
      set({ user: null });
      try {
        await sesion.cerrarSesion();
      } catch (e) {
        console.warn("No se pudo cerrar la sesion en el servidor:", e.message);
      }
    },

    homeFor: (user = get().user) => {
      if (!user) return "/";
      //  El admin de DataBridge va a su panel; el usuario de una empresa, al
      //  portal de empresas; el deudor, al de pago.
      if (user.role === "ADMIN") return "/admin";
      return user.role === "CREDITOR" ? "/databridge" : "/app";
    },
  };
});
