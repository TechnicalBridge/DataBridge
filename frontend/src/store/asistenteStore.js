import { create } from "zustand";

/**
 * Si el panel del asistente esta abierto. Lo abre la burbuja en el computador
 * y el icono del encabezado en el telefono, donde la burbuja tapaba los botones
 * para pagar (#303). Al estar aca, la conversacion sigue abierta al cambiar de
 * pagina.
 */
export const useAsistente = create((set) => ({
  abierto: false,
  alternar: () => set((s) => ({ abierto: !s.abierto })),
  cerrar: () => set({ abierto: false }),
}));
