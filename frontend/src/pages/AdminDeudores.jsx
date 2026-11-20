import { useEffect, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { buscarDeudores } from "../api/admin";
import Cargando from "../components/Cargando";
import { rutLegible } from "../utils/formato";

/** Lo minimo que se escribe para buscar, igual que en ms-debt. */
const MINIMO = 3;

/**
 * Buscar a un deudor en todas las empresas (#434), para atenderlo cuando
 * reclama o pide ayuda. Lo buscado va en la direccion (?q=), para volver a la
 * misma lista desde la ficha. Buscar no queda en la bitacora; abrir una ficha si.
 */
export default function AdminDeudores() {
  const [params, setParams] = useSearchParams();
  const q = (params.get("q") || "").trim();
  const pagina = Math.max(0, Number(params.get("pagina")) || 0);
  const [texto, setTexto] = useState(q);
  const [datos, setDatos] = useState(null);
  const [cargando, setCargando] = useState(false);
  const [error, setError] = useState("");
  //  Si se busca otra cosa mientras llega la respuesta anterior, esa se descarta.
  const ultima = useRef(0);

  useEffect(() => { setTexto(q); }, [q]);

  useEffect(() => {
    const esta = ++ultima.current;
    if (q.length < MINIMO) {
      setDatos(null);
      setError("");
      return;
    }
    setCargando(true);
    setError("");
    buscarDeudores(q, pagina)
      .then((r) => { if (esta === ultima.current) setDatos(r); })
      .catch((e) => { if (esta === ultima.current) { setDatos(null); setError(e.message); } })
      .finally(() => { if (esta === ultima.current) setCargando(false); });
  }, [q, pagina]);

  function buscar(e) {
    e.preventDefault();
    const limpio = texto.trim();
    if (limpio.length >= MINIMO) setParams({ q: limpio });
  }

  const irA = (p) => setParams(p > 0 ? { q, pagina: String(p) } : { q });
  const total = datos?.pagina?.totalElements ?? 0;
  const paginas = datos?.pagina?.totalPages ?? 0;
  const corto = texto.trim().length < MINIMO;

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <h1>Deudores</h1>
          <p>
            Busca a un deudor en todas las empresas, para atenderlo cuando reclama o pide ayuda. Su ficha muestra
            sus datos personales, y abrirla queda en la bitácora.
          </p>
        </div>
      </header>

      <div className="card aparece" style={{ "--i": 1 }}>
        <form className="buscar-deudor" onSubmit={buscar} role="search">
          <div className="field">
            <label htmlFor="buscar-deudor">RUT, nombre, correo o referencia de una deuda</label>
            <input id="buscar-deudor" type="search" value={texto} autoComplete="off" spellCheck={false}
                   aria-describedby="buscar-deudor-ayuda" placeholder="16.482.337-7 o Camila Fuentes"
                   onChange={(e) => setTexto(e.target.value)} />
          </div>
          <button type="submit" className="btn btn-primary" disabled={corto || cargando}>Buscar</button>
        </form>
        <p className="hint" id="buscar-deudor-ayuda">
          Sin importar puntos, guion, tildes ni mayúsculas. Al menos {MINIMO} letras o números.
        </p>

        {error ? <div className="error" role="alert">{error}</div> : null}

        {q.length < MINIMO ? null : !datos && cargando ? (
          <Cargando tarjetas={1} />
        ) : datos && datos.filas.length === 0 ? (
          <div className="empty" role="status">
            Nadie calza con «{q}». Prueba con el comienzo del RUT, o con otra parte del nombre o del correo.
          </div>
        ) : datos ? (
          <>
            <div className="tabla-scroll">
              <table className="table tabla-deudores" style={{ opacity: cargando ? 0.6 : 1 }} aria-busy={cargando}>
                <caption className="hint" aria-live="polite">
                  {total.toLocaleString("es-CL")} {total === 1 ? "deudor" : "deudores"} con «{q}»
                </caption>
                <thead>
                  <tr><th scope="col">Deudor</th><th scope="col">Deudas</th></tr>
                </thead>
                <tbody>
                  {datos.filas.map((d) => (
                    <tr key={d.rut}>
                      <td>
                        <Link to={`/admin/deudores/${encodeURIComponent(d.rut)}`} state={{ desde: `?${params}` }}>
                          <b>{d.nombre}</b>
                        </Link>
                        <span className="sub">RUT {rutLegible(d.rut)}</span>
                        {d.correo ? <span className="sub">{d.correo}</span> : null}
                      </td>
                      <td>
                        {d.deudas.toLocaleString("es-CL")} {d.deudas === 1 ? "deuda" : "deudas"}
                        <span className="sub">{d.empresas.join(", ")}</span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {paginas > 1 ? (
              <nav className="paginas" aria-label="Páginas de la búsqueda">
                <button type="button" className="btn btn-ghost btn-sm" disabled={pagina === 0 || cargando}
                        onClick={() => irA(pagina - 1)}>
                  Anteriores
                </button>
                <span className="hint" aria-live="polite">Página {pagina + 1} de {paginas}</span>
                <button type="button" className="btn btn-ghost btn-sm" disabled={pagina + 1 >= paginas || cargando}
                        onClick={() => irA(pagina + 1)}>
                  Siguientes
                </button>
              </nav>
            ) : null}
          </>
        ) : null}
      </div>
    </div>
  );
}
