import { useEffect, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { reclamosAbiertos, reclamosResueltos } from "../api/admin";
import Cargando from "../components/Cargando";
import Paginas from "../components/Paginas";
import { fecha, hoyEnChile } from "../utils/formato";

const POR_PAGINA = 20;

/** El motivo del reclamo, contado desde el panel. Los codigos son los del contrato. */
const MOTIVO = {
  no_reconoce: "No reconoce la deuda",
  ya_pagada: "Dice que ya la pagó",
  monto_incorrecto: "Dice que el monto no corresponde",
  otro: "Otro motivo",
};

/** Por que salio de la cobranza una deuda retirada en una carga, en palabras. */
const RETIRO = {
  pago_directo: "pagó directo a la empresa",
  acuerdo_directo: "llegó a un acuerdo directo con la empresa",
  error: "era un error de carga",
  fuera_de_mandato: "quedó fuera del mandato",
  otro: "otro motivo",
};

const enDias = (n) => (n === 0 ? "el mismo día" : n === 1 ? "1 día" : `${n} días`);

/** Como se cerro un reclamo, en una insignia y una frase. */
function cierre(r) {
  if (r.resultado === "reanudada") {
    return { insignia: "Reanudada", clase: "badge-wait", frase: "La empresa dijo que la deuda corresponde: volvió a cobranza." };
  }
  if (r.motivoRetiro === "disputa_resuelta") {
    return { insignia: "Retirada", clase: "badge-ok", frase: "La empresa le dio la razón al deudor: salió de la cobranza." };
  }
  return {
    insignia: "Retirada", clase: "badge-ok",
    frase: `Salió de la cobranza en una carga: ${RETIRO[r.motivoRetiro] || r.motivoRetiro || "sin motivo"}.`,
  };
}

/**
 * Los reclamos de los deudores en todas las empresas (#436): los abiertos, el
 * mas antiguo primero, con los dias que llevan sin respuesta, y los que se
 * cerraron en un periodo. El panel solo mira: el reclamo lo resuelve la empresa
 * que cobra. Sin datos del deudor; la referencia lleva a buscarlo en Deudores.
 */
export default function AdminReclamos() {
  const [params, setParams] = useSearchParams();
  const resueltos = params.get("ver") === "resueltos";
  const desde = params.get("desde") || "";
  const hasta = params.get("hasta") || "";
  const pagina = Math.max(0, Number(params.get("pagina")) || 0);
  const [datos, setDatos] = useState(null);
  const [cargando, setCargando] = useState(true);
  const [error, setError] = useState("");
  const [vuelta, setVuelta] = useState(0);
  //  Si se cambia de vista mientras llega la respuesta anterior, esa se descarta.
  const ultima = useRef(0);
  const clave = params.toString();

  useEffect(() => {
    const esta = ++ultima.current;
    setCargando(true);
    setError("");
    (resueltos
      ? reclamosResueltos({ desde, hasta, pagina, porPagina: POR_PAGINA })
      : reclamosAbiertos({ pagina, porPagina: POR_PAGINA }))
      .then((r) => { if (esta === ultima.current) setDatos({ ...r, resueltos }); })
      .catch((e) => { if (esta === ultima.current) setError(e.message); })
      .finally(() => { if (esta === ultima.current) setCargando(false); });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [clave, vuelta]);

  /** Cambia lo que se ve y vuelve a la primera pagina. */
  function ver(cambio) {
    const nuevos = { ver: resueltos ? "resueltos" : "", desde, hasta, ...cambio };
    if (nuevos.ver !== "resueltos") { nuevos.desde = ""; nuevos.hasta = ""; }
    setParams(Object.fromEntries(Object.entries(nuevos).filter(([, v]) => v)));
  }
  const irA = (p) => setParams(Object.fromEntries(Object.entries({
    ver: resueltos ? "resueltos" : "", desde, hasta, pagina: p > 0 ? String(p) : "",
  }).filter(([, v]) => v)));

  const total = datos?.pagina?.totalElements ?? 0;
  //  Lo que se ve es de la vista que se pidio: al cambiar, no se mezclan filas de la otra.
  const filas = datos && datos.resueltos === resueltos ? datos.filas : null;

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <h1>Reclamos</h1>
          <p>
            Los reclamos de los deudores en todas las empresas. Los resuelve la empresa que cobra: aquí se ve
            cuáles esperan respuesta y desde cuándo.
          </p>
        </div>
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setVuelta((v) => v + 1)} disabled={cargando}>
          Actualizar
        </button>
      </header>

      <div className="card aparece" style={{ "--i": 1 }}>
        <div className="filters" role="group" aria-label="Qué reclamos ver">
          <button type="button" className={`chip${resueltos ? "" : " on"}`} aria-pressed={!resueltos}
                  onClick={() => ver({ ver: "" })}>
            Abiertos
          </button>
          <button type="button" className={`chip${resueltos ? " on" : ""}`} aria-pressed={resueltos}
                  onClick={() => ver({ ver: "resueltos" })}>
            Resueltos
          </button>
        </div>

        {resueltos ? (
          <div className="filtros-accesos">
            <div className="field">
              <label htmlFor="reclamos-desde">Cerrados desde</label>
              <input id="reclamos-desde" type="date" value={desde} max={hasta || hoyEnChile()}
                     onChange={(e) => ver({ desde: e.target.value })} />
            </div>
            <div className="field">
              <label htmlFor="reclamos-hasta">Hasta</label>
              <input id="reclamos-hasta" type="date" value={hasta} min={desde || undefined} max={hoyEnChile()}
                     onChange={(e) => ver({ hasta: e.target.value })} />
            </div>
            {desde || hasta ? (
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => ver({ desde: "", hasta: "" })}>
                Últimos 30 días
              </button>
            ) : null}
          </div>
        ) : null}
        <p className="hint" style={{ marginTop: resueltos ? 0 : 12 }}>
          {resueltos
            ? desde || hasta ? "Los que se cerraron en esas fechas, lo más nuevo primero." : "Los que se cerraron en los últimos 30 días, lo más nuevo primero."
            : "El más antiguo primero. La referencia lleva a buscar al deudor en Deudores."}
        </p>

        {error ? <div className="error" role="alert">{error}</div> : null}

        {!filas && cargando ? (
          <Cargando tarjetas={1} />
        ) : filas && filas.length === 0 ? (
          <div className="empty">
            {resueltos ? "No se cerró ningún reclamo en esas fechas." : "No hay reclamos abiertos: ningún deudor espera respuesta."}
          </div>
        ) : filas ? (
          <>
            <div className="tabla-scroll">
              <table className="table tabla-reclamos" style={{ opacity: cargando ? 0.6 : 1 }} aria-busy={cargando}>
                <caption className="hint" aria-live="polite">
                  {total.toLocaleString("es-CL")} {total === 1 ? "reclamo" : "reclamos"}
                  {resueltos ? (total === 1 ? " cerrado" : " cerrados") : total === 1 ? " abierto" : " abiertos"}
                </caption>
                <thead>
                  <tr>
                    <th scope="col">Reclamo</th>
                    <th scope="col">{resueltos ? "Cómo se cerró" : "Sin respuesta"}</th>
                  </tr>
                </thead>
                <tbody>
                  {filas.map((r) => {
                    const c = resueltos ? cierre(r) : null;
                    return (
                      <tr key={`${r.deuda}-${r.abierto}`}>
                        <td>
                          <b>{MOTIVO[r.motivo] || r.motivo}</b>
                          {r.detalle ? <span className="sub reclamo-texto">«{r.detalle}»</span> : null}
                          <span className="sub">
                            Responde {r.cobra}
                            {r.acreedor !== r.cobra ? `, para ${r.acreedor}` : ""}
                          </span>
                          <span className="sub">
                            <Link to={`/admin/deudores?q=${encodeURIComponent(r.referencia)}`}>{r.referencia}</Link>
                            {" · "}{r.concepto}{r.campana ? ` · ${r.campana}` : ""}
                          </span>
                        </td>
                        <td>
                          {resueltos ? (
                            <>
                              <span className={`badge ${c.clase}`}>{c.insignia}</span>
                              <span className="sub">{fecha(r.resuelto)}, en {enDias(r.dias)}</span>
                              <span className="sub">{c.frase}</span>
                              {r.nota ? <span className="sub reclamo-texto">Nota de la empresa: «{r.nota}»</span> : null}
                            </>
                          ) : (
                            <>
                              <span className={`badge ${r.dias > 0 ? "badge-warn" : "badge-muted"}`}>
                                {r.dias === 0 ? "Hoy" : r.dias === 1 ? "1 día" : `${r.dias} días`}
                              </span>
                              <span className="sub">Desde el {fecha(r.abierto)}</span>
                            </>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            <Paginas total={total} porPagina={POR_PAGINA} pagina={pagina} alCambiar={irA} etiqueta="Páginas de reclamos" />
          </>
        ) : null}
      </div>
    </div>
  );
}
