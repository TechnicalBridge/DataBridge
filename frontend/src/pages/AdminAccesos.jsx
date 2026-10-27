import { useEffect, useRef, useState } from "react";
import { accesos, accesosDeHoy } from "../api/admin";
import { fechaHora, hoyEnChile } from "../utils/formato";
import Cargando from "../components/Cargando";

/** Cada resultado de la bitacora, como se lo nombra en el panel. */
const RESULTADOS = {
  granted: { texto: "Entró", clase: "badge-ok", que: "Entró con su código o su enlace." },
  invalid: { texto: "No corresponde", clase: "badge-peligro", que: "El código no era el de ese RUT, o el enlace no existe o ya se usó." },
  expired: { texto: "Vencido", clase: "badge-warn", que: "El código ya había vencido." },
  exhausted: { texto: "Demasiados intentos", clase: "badge-peligro", que: "Se equivocó tantas veces con ese código que dejó de servir." },
  sent: { texto: "Enviado", clase: "badge-muted", que: "Pidió un código o un enlace, y salió el correo." },
  refused: { texto: "Rechazado", clase: "badge-peligro", que: "Lo pidió y no salió nada (el RUT no tiene correo registrado, o el correo no tiene cuenta), o su cuenta no tiene acceso." },
};

const METODOS = { code: "Código", magic_link: "Enlace" };

const SIN_FILTROS = { resultado: "", metodo: "", desde: "", hasta: "" };

/**
 * La bitacora de acceso (#141): quien entro y que accesos se rechazaron, para
 * ver si alguien prueba codigos contra un RUT. Viene de ms-auth por paginas,
 * lo mas reciente primero. El RUT llega a medias y no hay correos ni IP: nada
 * de esta pantalla identifica a una persona.
 */
export default function AdminAccesos() {
  const [filtros, setFiltros] = useState(SIN_FILTROS);
  const [pagina, setPagina] = useState(0);
  const [datos, setDatos] = useState(null);
  const [hoy, setHoy] = useState(null);
  const [error, setError] = useState("");
  const [cargando, setCargando] = useState(true);
  const [vuelta, setVuelta] = useState(0);
  //  Si se cambia un filtro mientras llega la respuesta anterior, esa se descarta.
  const ultima = useRef(0);

  useEffect(() => {
    const esta = ++ultima.current;
    setCargando(true);
    setError("");
    accesos({ ...filtros, pagina })
      .then((r) => { if (esta === ultima.current) setDatos(r); })
      .catch((e) => { if (esta === ultima.current) setError(e.message); })
      .finally(() => { if (esta === ultima.current) setCargando(false); });
  }, [filtros, pagina, vuelta]);

  useEffect(() => {
    accesosDeHoy().then(setHoy).catch(() => setHoy(null));
  }, [vuelta]);

  function filtrar(cambio) {
    setFiltros((antes) => ({ ...antes, ...cambio }));
    setPagina(0);
  }

  const hayFiltros = Object.values(filtros).some(Boolean);
  const total = datos?.pagina?.totalElements ?? 0;
  const paginas = datos?.pagina?.totalPages ?? 0;

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <h1>Accesos</h1>
          <p>
            Quién entró y qué accesos se rechazaron, lo más reciente primero. El RUT va a medias y
            ninguna fila muestra un correo ni una IP.
          </p>
        </div>
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setVuelta((v) => v + 1)} disabled={cargando}>
          Actualizar
        </button>
      </header>

      <div className="cifras-revision aparece" style={{ "--i": 1 }} aria-live="polite">
        <div className="cifra entran"><b>{hoy ? hoy.entraron : "—"}</b><span>entradas hoy</span></div>
        <div className="cifra rechazo"><b>{hoy ? hoy.rechazados : "—"}</b><span>rechazos hoy</span></div>
        <div className="cifra"><b>{hoy ? hoy.enviados : "—"}</b><span>códigos y enlaces enviados hoy</span></div>
      </div>

      <div className="card aparece" style={{ "--i": 2 }}>
        <div className="filters" role="group" aria-label="Filtrar por resultado">
          <button type="button" className={`chip${filtros.resultado ? "" : " on"}`} onClick={() => filtrar({ resultado: "" })}>
            Todos
          </button>
          {Object.entries(RESULTADOS).map(([clave, r]) => (
            <button type="button" key={clave} className={`chip${filtros.resultado === clave ? " on" : ""}`}
                    aria-pressed={filtros.resultado === clave} onClick={() => filtrar({ resultado: clave })}>
              {r.texto}
            </button>
          ))}
        </div>

        <div className="filtros-accesos">
          <div className="field">
            <label htmlFor="accesos-metodo">Por</label>
            <select id="accesos-metodo" value={filtros.metodo} onChange={(e) => filtrar({ metodo: e.target.value })}>
              <option value="">Código o enlace</option>
              {Object.entries(METODOS).map(([clave, texto]) => <option key={clave} value={clave}>{texto}</option>)}
            </select>
          </div>
          <div className="field">
            <label htmlFor="accesos-desde">Desde</label>
            <input id="accesos-desde" type="date" value={filtros.desde} max={filtros.hasta || hoyEnChile()}
                   onChange={(e) => filtrar({ desde: e.target.value })} />
          </div>
          <div className="field">
            <label htmlFor="accesos-hasta">Hasta</label>
            <input id="accesos-hasta" type="date" value={filtros.hasta} min={filtros.desde || undefined} max={hoyEnChile()}
                   onChange={(e) => filtrar({ hasta: e.target.value })} />
          </div>
          {hayFiltros ? (
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => { setFiltros(SIN_FILTROS); setPagina(0); }}>
              Quitar los filtros
            </button>
          ) : null}
        </div>

        {error ? <div className="error" role="alert">{error}</div> : null}

        {!datos && cargando ? (
          <Cargando tarjetas={1} />
        ) : datos && datos.filas.length === 0 ? (
          <div className="empty">{hayFiltros ? "No hay accesos con estos filtros." : "Todavía no hay accesos."}</div>
        ) : datos ? (
          <>
            <div className="tabla-scroll">
              <table className="table" style={{ opacity: cargando ? 0.6 : 1 }} aria-busy={cargando}>
                <caption className="hint">
                  {total.toLocaleString("es-CL")} {total === 1 ? "registro" : "registros"}
                  {filtros.resultado ? ` · ${RESULTADOS[filtros.resultado].texto}` : ""}
                </caption>
                <thead>
                  <tr><th scope="col">Cuándo y por dónde</th><th scope="col">RUT</th><th scope="col">Resultado</th></tr>
                </thead>
                <tbody>
                  {datos.filas.map((a, i) => (
                    <tr key={`${a.cuando}-${i}`}>
                      <td>
                        {fechaHora(a.cuando)}
                        <span className="sub">{METODOS[a.metodo] || a.metodo}</span>
                      </td>
                      <td className="num sin-corte">
                        {a.rut || <span className="sub">Sin RUT</span>}
                      </td>
                      <td>
                        <span className={`badge ${RESULTADOS[a.resultado]?.clase || "badge-muted"}`}
                              title={RESULTADOS[a.resultado]?.que}>
                          {RESULTADOS[a.resultado]?.texto || a.resultado}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {paginas > 1 ? (
              <nav className="paginas" aria-label="Páginas de la bitácora">
                <button type="button" className="btn btn-ghost btn-sm" disabled={pagina === 0 || cargando}
                        onClick={() => setPagina((p) => p - 1)}>
                  Más recientes
                </button>
                <span className="hint" aria-live="polite">Página {pagina + 1} de {paginas}</span>
                <button type="button" className="btn btn-ghost btn-sm" disabled={pagina + 1 >= paginas || cargando}
                        onClick={() => setPagina((p) => p + 1)}>
                  Más antiguos
                </button>
              </nav>
            ) : null}
          </>
        ) : null}

        <details className="hint">
          <summary>Qué significa cada resultado</summary>
          <ul className="regla-lista">
            {Object.values(RESULTADOS).map((r) => <li key={r.texto}><b>{r.texto}:</b> {r.que}</li>)}
          </ul>
          <p>
            Sin RUT es alguien del personal de una empresa, o un enlace que no existe. Varios «No corresponde»
            seguidos contra el mismo RUT son alguien probando códigos.
          </p>
        </details>
      </div>
    </div>
  );
}
