import { useEffect, useRef, useState } from "react";
import { bitacora } from "../api/admin";
import Cargando from "../components/Cargando";
import Paginas, { dePagina } from "../components/Paginas";
import { fechaHora, hoyEnChile, rutLegible } from "../utils/formato";

const POR_PAGINA = 25;
const SIN_FILTROS = { persona: "", accion: "", empresa: "", desde: "", hasta: "" };

const SERVICIOS = {
  "ms-auth": "Acceso",
  "ms-debt": "Deudas",
  "ms-payments": "Pagos",
  auth: "Acceso",
  debt: "Deudas",
  payments: "Pagos",
};

const ACCIONES = {
  persona_creada: "Creó una persona",
  admin_creado: "Creó un administrador",
  persona_deshabilitada: "Quitó un acceso",
  persona_habilitada: "Devolvió un acceso",
  sesion_cerrada: "Cerró una sesión",
  empresa_creada: "Creó una empresa",
  empresa_suspendida: "Suspendió una empresa",
  empresa_reactivada: "Reactivó una empresa",
  aviso_reintentado: "Reintentó un aviso",
  deudor_consultado: "Abrió la ficha de un deudor",
  suscripcion_activada: "Activó una suscripción",
  suscripcion_desactivada: "Desactivó una suscripción",
  suscripcion_secreto_cambiado: "Cambió el secreto de una suscripción",
  suscripcion_probada: "Probó una suscripción",
  uf_cargada: "Cargó una UF",
  cobro_liberado: "Liberó un cobro",
  pago_verificado: "Verificó un pago",
};

const OPCIONES_ACCION = Object.entries(ACCIONES);

/** Lo afectado, con el RUT de empresa cuando el registro lo tiene. */
function Sobre({ fila }) {
  return (
    <>
      <b>{fila.sobre || "Sin detalle"}</b>
      <span className="sub">
        {fila.empresa ? `Empresa ${rutLegible(fila.empresa)}` : "Sin empresa asociada"}
      </span>
    </>
  );
}

/**
 * La bitacora unica del panel (#443). El portal junta los tres servicios y
 * conserva lo que respondio si uno de ellos esta caido.
 */
export default function AdminBitacora() {
  const [edicion, setEdicion] = useState(SIN_FILTROS);
  const [filtros, setFiltros] = useState(SIN_FILTROS);
  const [datos, setDatos] = useState(null);
  const [pagina, setPagina] = useState(0);
  const [cargando, setCargando] = useState(true);
  const [error, setError] = useState("");
  const [vuelta, setVuelta] = useState(0);
  const ultima = useRef(0);

  useEffect(() => {
    const esta = ++ultima.current;
    setCargando(true);
    setError("");
    bitacora(filtros)
      .then((r) => { if (esta === ultima.current) setDatos(r); })
      .catch((e) => { if (esta === ultima.current) setError(e.message); })
      .finally(() => { if (esta === ultima.current) setCargando(false); });
  }, [filtros, vuelta]);

  function enviar(e) {
    e.preventDefault();
    setPagina(0);
    setFiltros(edicion);
  }

  function limpiar() {
    setEdicion(SIN_FILTROS);
    setFiltros(SIN_FILTROS);
    setPagina(0);
  }

  const hayFiltros = Object.values(filtros).some(Boolean);
  const lista = datos?.filas || [];
  const paginada = dePagina(lista, pagina, POR_PAGINA);

  return (
    <div>
      <header className="topbar aparece">
        <div>
          <h1>Bitácora</h1>
          <p>Todo lo hecho desde el panel, reunido desde Acceso, Deudas y Pagos, lo más reciente primero.</p>
        </div>
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setVuelta((v) => v + 1)}
                disabled={cargando}>Actualizar</button>
      </header>

      <section className="card bloque aparece" style={{ "--i": 1 }} aria-labelledby="filtros-bitacora">
        <h2 id="filtros-bitacora">Filtrar acciones</h2>
        <form className="filtros-bitacora" onSubmit={enviar}>
          <div className="field">
            <label htmlFor="bitacora-persona">Persona que actuó</label>
            <input id="bitacora-persona" value={edicion.persona} placeholder="admin@databridge.cl"
                   onChange={(e) => setEdicion((f) => ({ ...f, persona: e.target.value }))} />
          </div>
          <div className="field">
            <label htmlFor="bitacora-accion">Acción</label>
            <select id="bitacora-accion" value={edicion.accion}
                    onChange={(e) => setEdicion((f) => ({ ...f, accion: e.target.value }))}>
              <option value="">Todas las acciones</option>
              {OPCIONES_ACCION.map(([valor, texto]) => <option key={valor} value={valor}>{texto}</option>)}
            </select>
          </div>
          <div className="field">
            <label htmlFor="bitacora-empresa">Empresa</label>
            <input id="bitacora-empresa" value={edicion.empresa} placeholder="77.305.118-6"
                   onChange={(e) => setEdicion((f) => ({ ...f, empresa: e.target.value }))} />
          </div>
          <div className="field">
            <label htmlFor="bitacora-desde">Desde</label>
            <input id="bitacora-desde" type="date" value={edicion.desde}
                   max={edicion.hasta || hoyEnChile()}
                   onChange={(e) => setEdicion((f) => ({ ...f, desde: e.target.value }))} />
          </div>
          <div className="field">
            <label htmlFor="bitacora-hasta">Hasta</label>
            <input id="bitacora-hasta" type="date" value={edicion.hasta}
                   min={edicion.desde || undefined} max={hoyEnChile()}
                   onChange={(e) => setEdicion((f) => ({ ...f, hasta: e.target.value }))} />
          </div>
          <div className="acciones-filtros-bitacora">
            <button type="submit" className="btn btn-primary btn-sm" disabled={cargando}>Aplicar filtros</button>
            {hayFiltros || Object.values(edicion).some(Boolean) ? (
              <button type="button" className="btn btn-ghost btn-sm" onClick={limpiar}>Limpiar</button>
            ) : null}
          </div>
        </form>
      </section>

      {datos?.sinServicio?.length ? (
        <div className="aviso bloque aparece" role="status" style={{ "--i": 2 }}>
          No respondió {datos.sinServicio.map((s) => SERVICIOS[s]).join(", ")}. Se muestra lo que entregaron los demás servicios.
        </div>
      ) : null}
      {datos?.limitados?.length ? (
        <div className="aviso bloque aparece" role="status" style={{ "--i": 2 }}>
          Se muestran los 500 registros más recientes de {datos.limitados.map((s) => SERVICIOS[s]).join(", ")}.
          Aplica filtros para consultar los anteriores.
        </div>
      ) : null}
      {error ? <div className="error bloque" role="alert">{error}</div> : null}

      <section className="card aparece" style={{ "--i": 3 }} aria-labelledby="lista-bitacora">
        <div className="card-cab">
          <div>
            <h2 id="lista-bitacora">Acciones</h2>
            <span className="sub">{lista.length.toLocaleString("es-CL")} {lista.length === 1 ? "registro" : "registros"}</span>
          </div>
          {cargando && datos ? <span className="badge badge-muted">Actualizando…</span> : null}
        </div>

        {!datos && cargando ? <Cargando tarjetas={2} /> : lista.length === 0 ? (
          <div className="empty">{hayFiltros ? "No hay acciones con estos filtros." : "Todavía no hay acciones administrativas."}</div>
        ) : (
          <>
            <div className="tabla-scroll bitacora-escritorio">
              <table className="table tabla-bitacora" style={{ opacity: cargando ? 0.65 : 1 }} aria-busy={cargando}>
                <thead><tr><th>Cuándo</th><th>Quién</th><th>Qué hizo</th><th>Sobre qué</th></tr></thead>
                <tbody>
                  {paginada.filas.map((fila) => (
                    <tr key={`${fila.servicio}-${fila.id}`}>
                      <td className="sin-corte">
                        {fechaHora(fila.cuando)}
                        <span className="sub">{SERVICIOS[fila.servicio] || fila.servicio}</span>
                      </td>
                      <td className="correo-bitacora">{fila.quien}</td>
                      <td><span className="badge badge-muted">{ACCIONES[fila.accion] || fila.accion}</span></td>
                      <td><Sobre fila={fila} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="bitacora-movil" style={{ opacity: cargando ? 0.65 : 1 }} aria-busy={cargando}>
              {paginada.filas.map((fila) => (
                <article className="bitacora-tarjeta" key={`${fila.servicio}-${fila.id}`}>
                  <div className="bitacora-cab">
                    <b>{fechaHora(fila.cuando)}</b>
                    <span className="sub">{SERVICIOS[fila.servicio] || fila.servicio}</span>
                  </div>
                  <span className="sub correo-bitacora">{fila.quien}</span>
                  <div><span className="badge badge-muted">{ACCIONES[fila.accion] || fila.accion}</span></div>
                  <Sobre fila={fila} />
                </article>
              ))}
            </div>
            <Paginas total={lista.length} porPagina={POR_PAGINA} pagina={paginada.enPagina}
                     alCambiar={setPagina} etiqueta="Páginas de la bitácora" />
          </>
        )}
      </section>
    </div>
  );
}
