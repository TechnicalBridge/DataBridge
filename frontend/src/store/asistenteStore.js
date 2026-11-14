import { create } from "zustand";

/**
 * Si el panel del asistente esta abierto. Lo abre el boton del menu en el
 * computador o el del encabezado en el telefono, que no tapan los botones para
 * pagar como la burbuja de antes (#303). Al estar aca, la conversacion sigue
 * abierta al cambiar de pagina.
 */
export const useAsistente = create((set) => ({
  abierto: false,
  alternar: () => set((s) => ({ abierto: !s.abierto })),
  cerrar: () => set({ abierto: false }),
}));
