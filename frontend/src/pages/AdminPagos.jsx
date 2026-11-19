import { useEffect, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { listarEmpresas, pagosDelPanel } from "../api/admin";
import Cargando from "../components/Cargando";
import Paginas from "../components/Paginas";
import { dinero, ESTADO_DE_PAGO, fechaHora, hoyEnChile, PASARELA, rutLegible } from "../utils/formato";

const FILTROS = ["estado", "pasarela", "empresa", "desde", "hasta", "revisar"];
const POR_PAGINA = 20;

/**
 * Los pagos de todas las empresas (#435), para detectar y resolver los que
 * quedaron sin conciliar. Los filtros van en la direccion, para volver a la
 * misma lista desde el detalle. Sin datos del deudor: para eso, su ficha.
 */
export default function AdminPagos() {
  const [params, setParams] = useSearchParams();
  const filtros = Object.fromEntries(FILTROS.map((f) => [f, params.get(f) || ""]));
  const pagina = Math.max(0, Number(params.get("pagina")) || 0);
  const [datos, setDatos] = useState(null);
  const [empresas, setEmpresas] = useState([]);
  const [cargando, setCargando] = useState(true);
  const [error, setError] = useState("");
  const [vuelta, setVuelta] = useState(0);
  //  Si se cambia un filtro mientras llega la respuesta anterior, esa se descarta.
  const ultima = useRef(0);
  const clave = params.toString();

  useEffect(() => {
    listarEmpresas().then(setEmpresas).catch(() => setEmpresas([]));
  }, []);

  useEffect(() => {
    const esta = ++ultima.current;
    setCargando(true);
    setError("");
    pagosDelPanel({ ...filtros, revisar: filtros.revisar === "si", pagina, porPagina: POR_PAGINA })
      .then((r) => { if (esta === ultima.current) setDatos(r); })
      .catch((e) => { if (esta === ultima.current) setError(e.message); })
      .finally(() => { if (esta === ultima.current) setCargando(false); });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [clave, vuelta]);

  function filtrar(cambio) {
    const nuevos = { ...filtros, ...cambio };
    setParams(Object.fromEntries(Object.entries(nuevos).filter(([, v]) => v)));
  }

  const irA = (p) => setParams({ ...Object.fromEntries(Object.entries(filtros).filter(([, v]) => v)),
    ...(p > 0 ? { pagina: String(p) } : {}) });
  const nombreDe = (rut) => empresas.find((e) => e.rut === rut)?.nombre || rutLegible(rut);
  const hayFiltros = Object.values(filtros).some(Boolean);
  const total = datos?.pagina?.totalElements ?? 0;

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <h1>Pagos</h1>
          <p>
            Los pagos de todas las empresas, el más nuevo primero. Sin datos del deudor: para eso, su ficha en
            Deudores.
          </p>
        </div>
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setVuelta((v) => v + 1)} disabled={cargando}>
          Actualizar
        </button>
      </header>

      <div className="card aparece" style={{ "--i": 1 }}>
        <div className="filters" role="group" aria-label="Qué pagos ver">
          <button type="button" className={`chip${filtros.revisar ? "" : " on"}`} aria-pressed={!filtros.revisar}
                  onClick={() => filtrar({ revisar: "" })}>
            Todos
          </button>
          <button type="button" className={`chip${filtros.revisar ? " on" : ""}`} aria-pressed={!!filtros.revisar}
                  onClick={() => filtrar({ revisar: "si" })}>
            Para revisar
          </button>
        </div>
        <p className="hint" style={{ marginTop: 0 }}>
          Para revisar: los abiertos hace más de una hora y los cobrados dos veces.
        </p>

        <div className="filtros-accesos">
          <div className="field">
            <label htmlFor="pagos-estado">Estado</label>
            <select id="pagos-estado" value={filtros.estado} onChange={(e) => filtrar({ estado: e.target.value })}>
              <option value="">Todos</option>
              {Object.entries(ESTADO_DE_PAGO).map(([clave2, e]) => <option key={clave2} value={clave2}>{e.texto}</option>)}
            </select>
          </div>
          <div className="field">
            <label htmlFor="pagos-pasarela">Pasarela</label>
            <select id="pagos-pasarela" value={filtros.pasarela} onChange={(e) => filtrar({ pasarela: e.target.value })}>
              <option value="">Todas</option>
              {Object.entries(PASARELA).map(([clave2, nombre]) => <option key={clave2} value={clave2}>{nombre}</option>)}
            </select>
          </div>
          <div className="field">
            <label htmlFor="pagos-empresa">Empresa acreedora</label>
            <select id="pagos-empresa" value={filtros.empresa} onChange={(e) => filtrar({ empresa: e.target.value })}>
              <option value="">Todas</option>
              {empresas.map((e) => <option key={e.rut} value={e.rut}>{e.nombre}</option>)}
            </select>
          </div>
          <div className="field">
            <label htmlFor="pagos-desde">Desde</label>
            <input id="pagos-desde" type="date" value={filtros.desde} max={filtros.hasta || hoyEnChile()}
                   onChange={(e) => filtrar({ desde: e.target.value })} />
          </div>
          <div className="field">
            <label htmlFor="pagos-hasta">Hasta</label>
            <input id="pagos-hasta" type="date" value={filtros.hasta} min={filtros.desde || undefined} max={hoyEnChile()}
                   onChange={(e) => filtrar({ hasta: e.target.value })} />
          </div>
          {hayFiltros ? (
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => setParams({})}>Quitar los filtros</button>
          ) : null}
        </div>

        {error ? <div className="error" role="alert">{error}</div> : null}

        {!datos && cargando ? (
          <Cargando tarjetas={1} />
        ) : datos && datos.filas.length === 0 ? (
          <div className="empty">{hayFiltros ? "No hay pagos con estos filtros." : "Todavía no hay pagos."}</div>
        ) : datos ? (
          <>
            <div className="tabla-scroll">
              <table className="table tabla-pagos" style={{ opacity: cargando ? 0.6 : 1 }} aria-busy={cargando}>
                <caption className="hint" aria-live="polite">
                  {total.toLocaleString("es-CL")} {total === 1 ? "pago" : "pagos"}
                </caption>
                <thead>
                  <tr><th scope="col">Pago</th><th scope="col">Monto</th></tr>
                </thead>
                <tbody>
                  {datos.filas.map((p) => (
                    <tr key={p.id}>
                      <td>
                        <Link to={`/admin/pagos/${p.id}`} state={{ desde: `?${clave}` }}>
                          <b>Pago {p.id} · {PASARELA[p.pasarela] || p.pasarela}</b>
                        </Link>
                        <span className="sub">{fechaHora(p.creado)}</span>
                        <span className="sub">{nombreDe(p.acreedorRut)} · deuda {p.deuda}</span>
                      </td>
                      <td>
                        <b>{dinero(p.monto, p.moneda)}</b>
                        <span className="sub insignias-pago">
                          <span className={`badge ${ESTADO_DE_PAGO[p.estado]?.clase || "badge-muted"}`}>
                            {ESTADO_DE_PAGO[p.estado]?.texto || p.estado}
                          </span>
                          {p.abiertoHaceMasDeUnaHora ? <span className="badge badge-warn">Abierto hace más de una hora</span> : null}
                          {p.duplicado ? <span className="badge badge-peligro">Cobrado dos veces</span> : null}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <Paginas total={total} porPagina={POR_PAGINA} pagina={pagina} alCambiar={irA} etiqueta="Páginas de pagos" />
          </>
        ) : null}
      </div>
    </div>
  );
}
