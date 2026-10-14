/**
 * Iconos de trazo, del color del texto que los rodea. Dibujados aca y no
 * traidos de una libreria: son pocos y asi el portal no suma otra dependencia.
 */
function Trazo({ size = 18, children, ...resto }) {
  return (
    <svg className="icono" width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" {...resto}>
      {children}
    </svg>
  );
}

export const IconoCheck = (p) => <Trazo {...p}><path d="M5 12.5l4.2 4.2L19 7" /></Trazo>;

export const IconoFlecha = (p) => <Trazo {...p}><path d="M5 12h14M13 6l6 6-6 6" /></Trazo>;

export const IconoVolver = (p) => <Trazo {...p}><path d="M19 12H5M11 18l-6-6 6-6" /></Trazo>;

export const IconoSol = (p) => (
  <Trazo {...p}>
    <circle cx="12" cy="12" r="4" />
    <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
  </Trazo>
);

export const IconoLuna = (p) => <Trazo {...p}><path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z" /></Trazo>;

export const IconoChat = (p) => (
  <Trazo {...p}><path d="M21 12a8 8 0 0 1-11.6 7.1L4 20.5l1.4-4.6A8 8 0 1 1 21 12z" /></Trazo>
);

export const IconoCerrar = (p) => <Trazo {...p}><path d="M6 6l12 12M18 6L6 18" /></Trazo>;

export const IconoPanel = (p) => (
  <Trazo {...p}>
    <rect x="3.5" y="3.5" width="7" height="7" rx="1.5" />
    <rect x="13.5" y="3.5" width="7" height="7" rx="1.5" />
    <rect x="3.5" y="13.5" width="7" height="7" rx="1.5" />
    <rect x="13.5" y="13.5" width="7" height="7" rx="1.5" />
  </Trazo>
);

export const IconoCandado = (p) => (
  <Trazo {...p}><rect x="4" y="11" width="16" height="10" rx="2.5" /><path d="M8 11V8a4 4 0 0 1 8 0v3" /></Trazo>
);

export const IconoCalendario = (p) => (
  <Trazo {...p}><rect x="3" y="5" width="18" height="16" rx="2.5" /><path d="M3 10h18M8 3v4M16 3v4" /></Trazo>
);

export const IconoMegafono = (p) => (
  <Trazo {...p}><path d="M4 10v4a1 1 0 0 0 1 1h3l7 4V5L8 9H5a1 1 0 0 0-1 1z" /><path d="M19 9.5a3.5 3.5 0 0 1 0 5M8 15l1.5 5" /></Trazo>
);
export const IconoDocumento = (p) => (
  <Trazo {...p}><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" /><path d="M14 3v5h5M9 13h6M9 17h4" /></Trazo>
);

export const IconoSalir = (p) => (
  <Trazo {...p}><path d="M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3M10 17l5-5-5-5M15 12H4" /></Trazo>
);

export const IconoCartera = (p) => (
  <Trazo {...p}><rect x="3" y="6" width="18" height="14" rx="2.5" /><path d="M16 13h2M3 10h18M7 6V4h10v2" /></Trazo>
);

export const IconoRecibo = (p) => (
  <Trazo {...p}><path d="M6 3h12v18l-3-2-3 2-3-2-3 2z" /><path d="M9 8h6M9 12h6M9 16h3" /></Trazo>
);

export const IconoUsuario = (p) => (
  <Trazo {...p}><circle cx="12" cy="8" r="4" /><path d="M4 21a8 8 0 0 1 16 0" /></Trazo>
);

export const IconoAyuda = (p) => (
  <Trazo {...p}><circle cx="12" cy="12" r="9" /><path d="M9.5 9a2.5 2.5 0 0 1 4.8 1c0 1.7-2.3 2-2.3 3.5M12 17h.01" /></Trazo>
);

export const IconoAlerta = (p) => (
  <Trazo {...p}><path d="M12 3.5l9 15.5H3z" /><path d="M12 10v4M12 16.8h.01" /></Trazo>
);

export const IconoSubir = (p) => <Trazo {...p}><path d="M12 16V4M6 10l6-6 6 6M4 20h16" /></Trazo>;

export const IconoDescargar = (p) => <Trazo {...p}><path d="M12 4v12M6 10l6 6 6-6M4 20h16" /></Trazo>;

export const IconoLlave = (p) => (
  <Trazo {...p}><circle cx="8" cy="15" r="4" /><path d="M11 12l9-9M16 7l3 3M14 9l2 2" /></Trazo>
);

export const IconoCopiar = (p) => (
  <Trazo {...p}><rect x="9" y="9" width="11" height="11" rx="2" /><path d="M5 15V5a2 2 0 0 1 2-2h10" /></Trazo>
);

/** El check que se dibuja solo, para cuando un pago queda listo. */
export function CheckAnimado({ size = 72 }) {
  return (
    <svg className="check-animado" width={size} height={size} viewBox="0 0 52 52" aria-hidden="true">
      <circle className="check-circulo" cx="26" cy="26" r="24" fill="none" />
      <path className="check-trazo" fill="none" d="M15 27l7.5 7.5L38 19" />
    </svg>
  );
}
