import { useEffect, useRef, useState } from "react";

export default function TablaDeslizable({ nombre, children }) {
  const contenedor = useRef(null);
  const [estado, setEstado] = useState("cabe");

  useEffect(() => {
    const region = contenedor.current;
    function medir() {
      const recorrido = region.scrollWidth - region.clientWidth;
      setEstado(recorrido <= 0 ? "cabe" : recorrido - region.scrollLeft > 1 ? "queda" : "fin");
    }

    const observador = new ResizeObserver(medir);
    observador.observe(region);
    observador.observe(region.firstElementChild);
    region.addEventListener("scroll", medir, { passive: true });
    medir();
    return () => {
      observador.disconnect();
      region.removeEventListener("scroll", medir);
    };
  }, []);

  const deslizable = estado !== "cabe";
  return (
    <>
      {deslizable && (
        <p className="tabla-scroll-pista" aria-hidden="true" style={{ visibility: estado === "fin" ? "hidden" : undefined }}>
          <span>↔</span> Desliza para ver más
        </p>
      )}
      <div ref={contenedor} className="tabla-scroll" tabIndex={deslizable ? 0 : undefined}
           role={deslizable ? "region" : undefined} aria-label={deslizable ? nombre : undefined}>
        {children}
      </div>
    </>
  );
}
